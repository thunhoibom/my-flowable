# Plan: Service Engine kiểu Flowable

Spec gốc: `F:\flowable\2026-11-13-flow-admin-engine-architecture-design.md`, mục "Service engine".

## Mục tiêu

Sửa phần runtime của Service Registry cho giống Service Engine của Flowable. Định nghĩa dịch vụ được deploy và có version giống BPMN. Mỗi version quy trình gắn với đúng bản dịch vụ được deploy cùng nó.

**Xong khi:** publish lại một dịch vụ rồi phát hành quy trình mới thì instance đang chạy trên version cũ vẫn gọi dịch vụ theo bản cũ. Có test chứng minh.

**Giữ nguyên:** phần soạn dịch vụ (`ServiceCatalog`, bản nháp, nút Publish dịch vụ, `ServiceCatalogController`), vì phần này sẽ chuyển sang Flow Admin BE. `service_publication` chỉ còn là **nguồn** lấy bản mới nhất khi phát hành quy trình. Runtime không đọc bảng này nữa.

## Flowable làm thế nào

Service Engine là bản Enterprise, không công khai mã nguồn. Tài liệu chỉ cho biết tên bảng (`FLW_SE_DEPLOYMENT`, `FLW_SE_DEPLOYMENT_RESOURCE`, `FLW_SE_DEFINITION`) và rằng file `.service` được deploy cùng app. Event Registry mã nguồn mở là engine "anh em" dựng cùng một khuôn: deployment, resource, definition, cùng `parentDeploymentId`. Các điểm dưới đây đọc từ mã nguồn Flowable 8.0.0 trong `~/.m2`:

| # | Hành vi | Nguồn |
|---|---|---|
| F1 | Deploy mà không truyền `parentDeploymentId` thì Flowable tự đặt `PARENT_DEPLOYMENT_ID_ = ID_` của chính deployment đó. Mỗi deployment BPMN tự là cha của chính nó. | `engine/impl/cmd/DeployCmd.java:116-121` |
| F2 | Resource của engine con (`.event`, `.channel`) được đặt **ngay trong deployment BPMN**. Một `EngineDeployer` gắn vào process engine (`customPostDeployers`) tách chúng ra, rồi tạo deployment con với `parentDeploymentId = id của deployment BPMN` và cùng tenant, cùng `name`. | `eventregistry/impl/configurator/deployer/EventDeployer.java`, `ProcessEngineConfigurationImpl.java:1682` |
| F3 | Xoá deployment BPMN thì `undeploy` xoá luôn các deployment con có cùng `parentDeploymentId`. | `EventDeployer.undeploy` |
| F4 | Version của definition bằng version mới nhất theo `(key, tenant)` cộng 1, bắt đầu từ 1. Id lấy từ `idGenerator`. Unique index trên `(KEY_, VERSION_, TENANT_ID_)`. | `EventDefinitionDeployer`, `flowable.postgres.create.eventregistry.sql` |
| F5 | Tra model theo key: có `parentDeploymentId` thì tìm deployment con theo cha (và tenant), lấy definition theo `(deployment, key)`. **Không thấy thì fallback sang bản mới nhất theo `(key, tenant)`**, rồi tới tenant mặc định nếu bật `fallbackToDefaultTenant`. Cờ `alwaysLookupLatestDefinitionVersion` bỏ qua deployment cha. | `eventregistry/impl/cmd/GetEventModelCmd.java` |
| F6 | Task tính deployment cha bằng `ProcessDefinitionUtil.getDefinitionDeploymentId(processDefinitionId)`: lấy `PARENT_DEPLOYMENT_ID_` của deployment quy trình, rỗng thì lấy chính `deploymentId`. | `engine/impl/util/ProcessDefinitionUtil.java:46-56` |
| F7 | Task DMN có field `sameDeployment`. `true` thì truyền deployment cha, `false` thì luôn lấy bản mới nhất. Không khai báo thì **mặc định truyền deployment cha**. | `DmnActivityBehavior.applyParentDeployment` |
| F8 | Cache definition là `DefaultDeploymentCache` (LRU) theo definition id, có giới hạn cấu hình được. Xoá deployment thì gỡ khỏi cache. | `EventRegistryEngineConfiguration:441`, `EventDeploymentManager.removeDeployment` |

## Thiết kế

### Schema (`V41__service_engine.sql`)

Chép theo schema `FLW_SE_*` (ảnh schema) và kiểu cột của `FLW_EVENT_*` trong Flowable 8.0.0:

```sql
CREATE TABLE flw_se_deployment (
    id_                   VARCHAR(255) NOT NULL,
    name_                 VARCHAR(255),
    key_                  VARCHAR(255),
    category_             VARCHAR(255),
    deploy_time_          TIMESTAMP(3) WITHOUT TIME ZONE,
    tenant_id_            VARCHAR(255) DEFAULT '',
    parent_deployment_id_ VARCHAR(255),
    CONSTRAINT flw_se_deployment_pkey PRIMARY KEY (id_)
);

CREATE TABLE flw_se_deployment_resource (
    id_             VARCHAR(255) NOT NULL,
    name_           VARCHAR(255),
    deployment_id_  VARCHAR(255),
    resource_bytes_ BYTEA,
    CONSTRAINT flw_se_deployment_resource_pkey PRIMARY KEY (id_)
);
ALTER TABLE flw_se_deployment_resource
    ADD CONSTRAINT flw_fk_se_rsrc_dpl FOREIGN KEY (deployment_id_) REFERENCES flw_se_deployment (id_);
CREATE INDEX flw_idx_se_rsrc_dpl ON flw_se_deployment_resource (deployment_id_);

CREATE TABLE flw_se_definition (
    id_            VARCHAR(255) NOT NULL,
    rev_           INTEGER      NOT NULL,
    key_           VARCHAR(255),
    deployment_id_ VARCHAR(255) NOT NULL,
    tenant_id_     VARCHAR(255) DEFAULT '',
    name_          VARCHAR(255),
    version_       INTEGER,
    resource_name_ VARCHAR(255),
    CONSTRAINT flw_se_definition_pkey PRIMARY KEY (id_)
);
CREATE UNIQUE INDEX flw_idx_se_def_uniq ON flw_se_definition (key_, version_, tenant_id_);
CREATE INDEX flw_idx_se_depl_parent ON flw_se_deployment (parent_deployment_id_);
```

- Không đặt RLS. Các bảng `ACT_*`, `FLW_EVENT_*` của Flowable cũng không có RLS. Tenant được lọc bằng `tenant_id_` trong query, giống Flowable.
- `flw_idx_se_depl_parent` là index thêm vào cho phép tra theo cha (F5), không làm đổi schema.

### Deploy (F1, F2, F4)

- `ProcessDeployer.deploy` thêm `addBytes("<serviceKey>.service", json)` vào **chính deployment BPMN** cho mỗi dịch vụ mà BPMN dùng. Không truyền `parentDeploymentId`, vì Flowable tự đặt (F1).
- `ServiceDeployer implements EngineDeployer`: chép theo `EventDeployer`, gắn qua `customPostDeployers` trong `FlowableEngineConfig`.
  - Khi deployment mới có resource `*.service`: `serviceRepositoryService.createDeployment().name(deployment.getName()).parentDeploymentId(deployment.getId()).tenantId(...)`, thêm từng resource rồi `deploy()`.
  - `undeploy(parent, cascade)`: xoá các deployment dịch vụ có cùng cha (F3).
- Tất cả chạy trong command của `DeployCmd`, tức nằm trong giao dịch Spring đang có của `ProcessPublishServiceImpl`.
- Rollback về vK: deploy lại **toàn bộ resource của deployment vK**, gồm BPMN và các file `.service`. Không lấy bản dịch vụ mới nhất. Giống Flowable deploy lại một app cũ.

### Tra định nghĩa lúc chạy (F5, F6, F7)

- `ServiceRegistryDelegate`:
  - Tính `parentDeploymentId` bằng `ProcessDefinitionUtil.getDefinitionDeploymentId(execution.getProcessDefinitionId())` (F6).
  - Attribute `sameDeployment` trên phần mở rộng của service task: `false` thì không truyền cha. Không khai báo thì mặc định truyền cha (F7).
- `ServiceRepositoryService.getServiceModelByKeyAndParentDeploymentId(key, parentDeploymentId, tenantId)`: chép logic `GetEventModelCmd` (F5):
  1. Tìm deployment theo cha và tenant, lấy definition theo `(deployment, key)`.
  2. Không thấy thì lấy bản mới nhất theo `(key, tenant)`.
  3. Vẫn không thấy và bật `fallbackToDefaultTenant` thì lấy bản mới nhất ở tenant mặc định.
  4. Vẫn không thấy thì ném `ServiceCallException("SERVICE_NOT_DEPLOYED")`.
- Cờ `workflow.service-registry.always-lookup-latest-definition-version` (mặc định `false`).
- Cache theo definition id (F8): `workflow.service-registry.definition-cache-limit`, mặc định 200 theo tài liệu Work. Xoá deployment thì gỡ khỏi cache.

### Nội dung `.service`

Chỉ chứa phần thiết kế: operation, tham số, body, luật lỗi, multi-step, kiểu auth. Không chứa base URL hay secret. Lúc chạy, phần này được ghép với cấu hình môi trường hiện tại của dịch vụ (`service_definition` và `service_operation_draft`, sau này là `work_service_environment`). Cách này giống Flowable: định nghĩa có thể dùng biểu thức, còn URL lấy từ `flowable.service-registry.rest-settings.<key>.baseUrl` theo môi trường.

## Task

### Task 1: Migration V41

- Viết `V41__service_engine.sql` như trên, và thêm cột `parent_deployment_id VARCHAR(255)` vào `service_callback_wait`.
- Kiểm: `PlatformMigrationIntegrationTest` chạy qua V41.

### Task 2: API và JDBC của service engine (module `service-registry`, package `serviceregistry.engine`)

- API, theo mẫu `EventRepositoryService`:
  - `ServiceRepositoryService`: `createDeployment()`, `createDeploymentQuery()`, `createServiceDefinitionQuery()`, `deleteDeployment(id)`, `getServiceModelByKey(key, tenantId)` và `getServiceModelByKeyAndParentDeploymentId(key, parentDeploymentId, tenantId)`.
  - `ServiceDeploymentBuilder`: `name`, `key`, `category`, `tenantId`, `parentDeploymentId`, `addServiceDefinitionBytes(resourceName, bytes)`, `deploy()`.
  - `ServiceDeployment`, `ServiceDefinition`: getter khớp với các cột.
- `JdbcServiceRepositoryService`:
  - Đọc và ghi qua `JdbcTemplate`.
  - Tính version theo F4.
  - Id là UUID (`StrongUuidGenerator`, cùng loại Flowable đang dùng trong dự án; nếu không thì dùng `UUID.randomUUID`).
  - Lookup theo F5. Cache theo F8.
- Test `JdbcServiceRepositoryServiceTest` (Testcontainers, mẫu `JdbcServiceCatalogTest`):
  - version 1, 2 theo key; key khác bắt đầu lại từ 1; tenant khác bắt đầu lại từ 1;
  - tra theo cha trả đúng bản;
  - cha không có key thì fallback sang bản mới nhất;
  - chưa từng deploy thì ném `SERVICE_NOT_DEPLOYED`;
  - xoá deployment thì xoá resource và definition.

### Task 3: `ServiceDeployer` gắn vào process engine

- `ServiceDeployer implements EngineDeployer` (F2, F3), đăng ký trong `FlowableEngineConfig` qua `setCustomPostDeployers`.
- Test (engine Flowable in-memory hoặc Testcontainers):
  - deploy BPMN kèm `a.service` thì có một `flw_se_deployment` với `parent_deployment_id_ = id deployment BPMN`, cùng tenant;
  - `repositoryService.deleteDeployment(id, true)` thì deployment dịch vụ bị xoá;
  - deployment không có `.service` thì không tạo gì.

### Task 4: `ProcessDeployer` đưa `.service` vào deployment

- Lấy các `serviceKey` mà BPMN dùng qua `XmlServiceTaskBindingParser`. Parser này phải bắt được cả service task nằm trong subprocess.
- Với mỗi key: lấy `ServiceCatalog.publishedService(key)`, bỏ URL và secret (`PublishedService.designOnly()`), serialize thành `<key>.service`. Key nào chưa publish thì ném `BusinessException(BAD_REQUEST)` kèm danh sách.
- `workflow-engine` không phụ thuộc `service-registry`, nên thêm port `ServiceDefinitionSource` và adapter ở `workflow-boot`.
- Rollback: `ProcessPublishServiceImpl.rollback` deploy lại mọi resource của deployment vK (`repositoryService.getDeploymentResourceNames` và `getResourceAsStream`), không dựng lại từ catalog.
- Test: sửa `ProcessPublishServiceImplTest`; thêm ca dịch vụ chưa publish thì bị từ chối, và ca rollback mang theo `.service` của vK.

### Task 5: Runtime

- `ServiceRegistryGateway.findPublished` và `invoke` thêm `ServiceScope(tenantId, parentDeploymentId)`. `parentDeploymentId` là null khi `sameDeployment=false`.
- `ServiceRegistryDelegate` tính scope theo F6 và F7.
- `PublishedServiceInvoker`:
  - đọc định nghĩa từ `ServiceRepositoryService` thay cho `catalog.publishedService`;
  - ghép base URL, `sharedAuth` và secret từ cấu hình hiện tại (`ServiceEnvironmentConfig`, đọc từ catalog).
- Callback:
  - `CallbackWait` và `JdbcCallbackWaitStore` thêm `parentDeploymentId`;
  - `ServiceCallbackService.complete` tra theo scope đã lưu.
- Sau task này, ngoài phần soạn không còn chỗ nào gọi `catalog.publishedService`. Kiểm bằng grep.
- Test: sửa `ServiceRegistryGatewayAdapterTest` và `ServiceCallbackServiceTest`; thêm ca đổi base URL sau khi deploy thì lần gọi sau dùng URL mới.

### Task 6: Test tích hợp chứng minh mục tiêu

`ServiceEngineVersioningIT` (workflow-boot, Testcontainers và `HttpServer` của JDK):

1. Publish dịch vụ S với `path=/a`. Phát hành quy trình P gồm user task rồi tới service task gọi S. Start instance 1, cho dừng ở user task.
2. Sửa S thành `/b`, publish S, phát hành P v2. Start instance 2.
3. Hoàn thành user task của cả hai. Instance 1 phải gọi `/a`, instance 2 phải gọi `/b`.
4. Rollback P về v1 (tạo v3). Start instance 3, instance này phải gọi `/a`.
5. Service task có `sameDeployment="false"` thì luôn gọi bản mới nhất.

### Task 7: Hoàn tất

- Thêm hai property `workflow.service-registry.*` vào `application.yml`.
- Cập nhật mục "Service engine" trong spec gốc theo F1 đến F7. Bỏ ý dùng `publishId` làm deployment cha; `publishId` chỉ còn là `KEY_` dùng để chống deploy trùng.
- Chạy `mvn verify` toàn bộ `flow-engine`. Hai lỗi có sẵn từ trước (Checkstyle ở auth-engine, JaCoCo ở service-registry) không tính là lỗi mới, nhưng JaCoCo của service-registry không được giảm.

## Ngoài plan

- Bảng `work_service_environment` thật và màn Hub sửa URL/secret.
- `/api/deployment/processes` nhận JSON từ Flow Admin BE. Khi đó `.service` lấy từ request thay vì từ catalog.
- Màn Hub liệt kê dịch vụ đã deploy (`createServiceDefinitionQuery`).

## Rủi ro

- **Fallback sang bản mới nhất (F5):** nếu một deployment thiếu dịch vụ thì task lặng lẽ dùng bản mới nhất. Task 4 chặn trường hợp này ngay khi deploy, nên fallback chỉ còn tác dụng với instance cũ chưa có `.service`.
- **Instance start trước V41:** không có `.service` trong deployment, nên sẽ fallback sang bản mới nhất trong `flw_se_definition`. Nếu dịch vụ chưa từng được deploy theo cách mới thì báo `SERVICE_NOT_DEPLOYED`. Điều này đúng với quyết định không migrate dữ liệu.
- **Chung giao dịch:** `EngineDeployer` chạy trong command của Flowable. `JdbcTemplate` phải dùng chung `DataSource` và transaction manager với Flowable, nếu không thì phần ghi dịch vụ sẽ không rollback cùng BPMN. Test ở Task 3 phải có ca "BPMN lỗi thì không còn dòng `flw_se_*` nào".
