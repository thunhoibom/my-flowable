# Kiến trúc Flow Admin và Flow Engine

## Mục tiêu

Tách hệ thống thành các thành phần theo bảng thiết kế (Flow Admin, Flow Admin BE, Flow Engine, Postgres), và bám kiến trúc Flowable Enterprise:

- **Flow Admin BE** (`flow-admin-be`): gộp Flowable Design và Flowable Hub. Design để vẽ, soạn và phát hành. Hub để giám sát và vận hành runtime. Phần lớn thay đổi về sau sẽ nằm ở app này.
- **Flow Engine** (`flow-engine`): tương đương Flowable Work. Gồm engine Flowable và API runtime. App này phải **chạy ổn định**: chỉ đổi khi chủ ý nâng phiên bản, không đổi theo mỗi lần Design thay đổi.
- **Flow Admin** (`flow-admin`): frontend **dùng chung một project cho cả Design, Hub và Work**.

Tổng cộng **3 repository**, mỗi repo deploy riêng:

| Repo | Thành phần | Nội dung |
|---|---|---|
| `flow-engine` | Flow Engine | Backend chạy quy trình (Work) |
| `flow-admin-be` | Flow Admin BE | Backend Design+Hub |
| `flow-admin` | Flow Admin | Frontend chung, gồm ba khu vực: Design, Hub, Work |

Spec này chốt ranh giới, API giữa các repo và lộ trình. Mỗi giai đoạn ở mục cuối sẽ có spec và plan riêng.

### Thuật ngữ

- **Flow Admin, Flow Admin BE, Flow Engine** là tên thành phần, theo bảng thiết kế. Đây là tên dùng cho repo, image, app.
- **Design, Hub, Work** là tên khu vực chức năng, theo Flowable. Design và Hub nằm ở Flow Admin BE, Work là Flow Engine. Trên frontend, cả ba là ba khu vực của Flow Admin.
- **Admin API của Flow Engine** (`/api/admin/**`) là nhóm API vận hành do Flow Engine cung cấp cho Hub. Không nhầm với Flow Admin BE.

## Ánh xạ với bảng thành phần

| STT | Thành phần (bảng thiết kế) | Mô tả trong bảng | Ở đây | Số lượng deploy tối thiểu |
|---|---|---|---|---|
| 1 | Flow Admin | Giao diện quản trị, ReactJS | `flow-admin`: SPA khu vực Design, Hub, Work. Hiện dùng **Vue**, xem mục Rủi ro | 2 |
| 2 | Flow Admin BE | API quản trị cho giao diện, tương tác trực tiếp với CSDL, Java | `flow-admin-be`: Java, chỉ đọc và ghi schema `flow_admin` của mình; dữ liệu runtime lấy qua Admin API của Flow Engine | 2 |
| 3 | Flow Engine | Core xử lý flow nghiệp vụ theo cấu hình từ Flow Admin, dùng core Flowable | `flow-engine`: Flowable 8.0.0, nhận cấu hình qua Deployment API (JSON) từ Flow Admin BE | 2 |
| 4 | Postgres | CSDL cho Flow Admin và Flow Engine, Master-Slave | Dùng chung DB `workflow_agent`, mỗi bên một schema. Replication Master-Slave do hạ tầng lo, xem mục Triển khai | 2 |

Ngoài bảng, hệ thống còn dùng Redis (session của Flow Engine) và Keycloak/EIM (đăng nhập, role), coi là hạ tầng dùng chung.

## Tham chiếu Flowable Enterprise

Nguồn: [Full platform install](https://documentation.flowable.com/latest/admin/installs/platform-full/), [Design install](https://documentation.flowable.com/latest/admin/installs/design-quick), [Clustering](https://documentation.flowable.com/latest/admin/installs/clustering).

| Flowable làm | Ở đây làm |
|---|---|
| Design, Work, Hub là 3 WAR riêng, mỗi WAR có UI riêng | 2 backend (Design và Hub gộp thành Flow Admin BE, Work là Flow Engine), 1 frontend chung Flow Admin cho cả ba |
| Design publish App lên Work qua REST (`flowable.design.deployment-api-url`) | Giữ nguyên ý tưởng: Flow Admin BE gửi một request JSON (BPMN, dịch vụ, luật gán) qua REST tới Flow Engine |
| Có thể khai báo nhiều đích deploy (`flowable.design.deployment.<id>.*`) và bắt duyệt trước khi publish (`publish-request-required`) | Giữ nguyên, làm ở giai đoạn 5 |
| Hub gọi REST API của Work, không đọc DB của Work; mỗi Work là một "cluster" khai báo trong Hub | Giữ nguyên |
| Design/Hub có thể dùng chung DB với Work hoặc DB riêng | **Dùng chung DB `workflow_agent`**, mỗi bên một schema riêng |
| Mỗi app một user store, cùng nối vào một IdP qua OAuth2 | Cùng dùng Keycloak/EIM. Mỗi app tự kiểm quyền của mình |
| Work cần Elasticsearch, trừ trường hợp xử lý thẳng, tải nhẹ (`flowable.indexing.enabled=false`) | Chưa dùng ES |
| Khuyến nghị cả 3 app chạy cùng phiên bản | Không ép. Deployment API chỉ được thêm trường, không xoá hay đổi nghĩa trường cũ |

## Ngoài phạm vi

- Không viết lại engine, không đổi hành vi gốc của Flowable 8.0.0.
- Agent đang ẩn (`agentsEnabled = false`). Khi bật lại thì theo đúng ranh giới ở đây: định nghĩa nằm ở Design, chạy ở Flow Engine. Spec này không làm phần đó.
- Chưa làm CMMN, DMN, Form.
- Chưa dùng Elasticsearch, chưa làm chat/Engage.

## Tổng quan

```
 Browser
    │
    ▼
 ┌────────────────── Flow Admin (1 SPA, nginx) ──────────────────┐
 │  /design/**   /hub/**                    /work/**             │
 └──────┬───────────────────────────────────────────┬────────────┘
        │ /api/admin-be/**                          │ /api/engine/**
        ▼                                           │
 ┌───────────────────────── Flow Admin BE ──────────┼───────────────┐
 │  Design: bản nháp, designer, catalog dịch vụ,    │               │
 │          event, luật gán người,                 │               │
 │          lịch sử phát hành                       │               │
 │  Hub:    danh sách Flow Engine, giám sát         │               │
 │          instance/job, deploy, tenant/quyền,     │               │
 │          cấu hình môi trường                     │               │
 └───────┬──────────────────────────────┬───────────┼───────────────┘
         │ HTTPS: POST deployment JSON  │ HTTPS: Admin API
         │ (service token)              │ (token của người dùng)
         ▼                              ▼           ▼
 ┌──────────────────────────── Flow Engine ─────────────────────────┐
 │  Deployment API · Admin API · Runtime API                        │
 │  Flowable engine, service caller, callback, event inbound,       │◄── Hệ thống ngoài
 │  outbox, agent runtime, auth/tenant/RLS                          │
 └───────┬──────────────────────────────────────────────────────────┘
                            │
       ┌────────────────────┴──── Postgres: DB workflow_agent ─────────────────────┐
       │  schema public     ← Flow Engine   (ACT_*, runtime, auth, outbox, ...)    │
       │  schema flow_admin ← Flow Admin BE (bản nháp, catalog, lịch sử, cluster)  │
       └────────────────────────────────────────────────────────────────────────────┘
                            Redis ← Flow Engine (thêm Flow Admin BE khi chạy nhiều node)
                            Keycloak/EIM ← cả hai backend
```

## Nguyên tắc bất biến

1. **Một chiều.** Chỉ Flow Admin BE gọi sang Flow Engine. Flow Engine không bao giờ gọi ngược lại. Tắt Flow Admin BE thì Flow Engine vẫn chạy đủ: start, duyệt, callback, timer, event.
2. **Flow Engine tự đủ.** Mọi thứ một instance cần để chạy (BPMN, định nghĩa dịch vụ, luật gán người) đi vào Flow Engine qua một lần deploy và được gắn với deploy đó. Event/channel dùng chung đi vào Flow Engine qua một lần deploy event riêng. Runtime không tra cứu bản "mới nhất" của một định nghĩa do Design quản lý.
3. **Không đọc chéo dữ liệu.** Tuy dùng chung DB, mỗi app chỉ đọc và ghi schema của mình. Postgres ép việc này qua phân quyền user (xem mục Dữ liệu).
4. **Không có bí mật trong request deploy.** URL theo môi trường, credential và secret không nằm trong request deploy hay sơ đồ. Chúng ở Flow Engine, theo từng môi trường, và được sửa qua Hub.
5. **Flow Engine là bên kiểm cuối cùng.** Design kiểm trước để báo lỗi sớm cho người thiết kế. Flow Engine vẫn kiểm lại khi nhận deploy và có quyền từ chối.

## Trách nhiệm từng app

| Năng lực | Flow Admin BE | Flow Engine |
|---|---|---|
| Bản nháp quy trình, designer BPMN, kiểm khi lưu | ✔ | |
| Guard (script, subprocess, call activity, event, agent) | Chạy trước khi publish | Chạy lại khi nhận deploy (bên quyết định) |
| Catalog dịch vụ: soạn operation, tham số, luật lỗi | ✔ | |
| Catalog dịch vụ: base URL, credential theo môi trường | Màn hình Hub | Lưu và dùng |
| Gọi dịch vụ, nhiều bước, callback chờ | | ✔ |
| Event/channel: soạn | ✔ | |
| Event/channel: deploy vào event registry engine, nhận event | | ✔ |
| Luật gán người: soạn | ✔ | |
| Luật gán người: gắn vào definition, thực thi, fallback (chỉ đọc sau khi deploy) | | ✔ |
| Giao lại một task đang chạy | Màn hình Hub | Admin API |
| Lịch sử phát hành, rollback | ✔ | |
| Deploy, suspend/resume, xoá version | Ra lệnh | Thực hiện |
| Start, duyệt, từ chối, chuyển, interaction | | ✔ (Runtime API, khu vực Work của Flow Admin) |
| Danh sách và chi tiết instance, terminate, job lỗi, retry | Màn hình Hub | Admin API |
| Tenant, người dùng, role, RLS | Màn hình Hub | Lưu dữ liệu và thực thi |
| Outbox/inbox, audit vận hành | Màn hình Hub | ✔ |
| Danh sách các Flow Engine (cluster) | ✔ | |

## Repository và phụ thuộc giữa repo

```
              ┌── Design, Hub ──REST──► flow-admin-be ──────────REST─────────┐
 flow-admin ──┤                                                              ▼
              └── Work ─────────REST──────────────────────────────────► flow-engine ◄── hệ thống ngoài
```

- **Khu vực Design và Hub** của Flow Admin chỉ gọi `flow-admin-be`. Hub không gọi thẳng Flow Engine mà đi qua Flow Admin BE (BFF), vì Hub quản lý được nhiều Flow Engine (dev, staging, prod), trong khi frontend chỉ biết một Flow Engine.
- **Khu vực Work** của Flow Admin chỉ gọi Runtime API của `flow-engine`.
- `flow-admin-be` chỉ phụ thuộc vào `flow-engine` qua REST API. **Không có artifact Maven dùng chung.** Flow Admin BE tự khai báo DTO của request/response mình gọi.
- `flow-engine` không phụ thuộc repo nào.

### Không chia sẻ code

- **`shared-kernel`** (15 file: context, response, exception) được **chép** sang mỗi backend rồi phát triển độc lập. Nếu biến nó thành thư viện chung thì hai backend lại phải nâng version cùng lúc, và mục tiêu Flow Engine ổn định mất.
- Các luật kiểm BPMN (guard) nằm ở Flow Engine. Flow Admin BE chép phần cần để báo lỗi sớm; Flow Engine vẫn là bên quyết định.

## Backend Flow Engine: `flow-engine`

Lấy toàn bộ backend hiện tại (`workflow-agent-service`), không giữ lịch sử git. Lúc đầu repo này vẫn chứa cả code soạn thảo; code đó chuyển dần sang `flow-admin-be` theo lộ trình.

```
flow-engine/
  shared-kernel/
  auth-engine/
  rules-engine/
  service-registry/       (rút gọn) ServiceCaller, transport, callback;
                            service engine kiểu Flowable (flw_se_*): deploy,
                            version, tra định nghĩa theo deployment cha
  workflow-engine/        (rút gọn) chỉ giữ runtime và phía nhận deploy
  agent-engine/
  engine-migrations/      (đổi tên từ platform-migrations) schema public
  engine-boot/            (đổi tên từ workflow-boot)
```

## Backend Flow Admin BE: `flow-admin-be`

Repo mới, bắt đầu từ một khung gồm `shared-kernel` (bản chép) và `admin-be-boot`. Code soạn thảo được chuyển từ `flow-engine` sang theo lộ trình, không giữ lịch sử git.

```
flow-admin-be/
  shared-kernel/          (bản chép)
  design-modeling/        bản nháp quy trình, catalog nháp, event nháp, luật gán nháp,
                            lịch sử phát hành, client gọi Deployment API
  hub-admin/              danh sách cluster, BFF gọi Admin API của Flow Engine
  admin-be-migrations/    schema flow_admin
  admin-be-boot/
```

Hai repo build riêng, không có dependency Maven sang nhau, nên không thể import chéo.

## Dữ liệu

### Schema và user

| | Flow Engine | Flow Admin BE |
|---|---|---|
| Schema | `public` (giữ nguyên) | `flow_admin` (mới) |
| User DB | `flow_engine`: toàn quyền trên `public`, không có quyền gì trên `flow_admin` | `flow_admin_be`: toàn quyền trên `flow_admin`, không có quyền gì trên `public` |
| Flyway | `public.flyway_schema_history`, V1–V40 và tiếp tục đánh số | `flow_admin.flyway_schema_history`, đánh số lại từ V1 |
| RLS theo tenant | Giữ như V16 | Áp dụng cùng cơ chế cho mọi bảng có `tenant_id` |

Trên môi trường local và compose có thể dùng một superuser cho tiện, nhưng test tích hợp phải chạy với hai user tách quyền để chứng minh nguyên tắc 3.

### Bảng nào thuộc bên nào

| Bảng hiện có | Về đâu | Ghi chú |
|---|---|---|
| `workflow_process_draft` | flow_admin | Tạo bảng mới ở flow_admin, không chuyển dữ liệu (xem dưới) |
| `workflow_process_release` | Tách hai | Lịch sử phát hành phía Design ở flow_admin. Phía Flow Engine dùng luôn `ACT_RE_DEPLOYMENT` của Flowable |
| `service_definition`, `service_operation_draft` | flow_admin | Bỏ các cột secret và URL theo môi trường, xem mục Cấu hình môi trường |
| `service_publication` | Thay thế | Flow Engine dùng các bảng `flw_se_*`, gắn theo deploy. Bỏ dần bảng cũ |
| `workflow_event_registry_draft` | flow_admin | |
| `workflow_event_registry_release` | Tách hai | Giống `workflow_process_release` |
| `workflow_node_assignment_rule*`, `workflow_assignment_target` | public | Flow Engine giữ phần gắn theo definition id, chỉ ghi khi nhận deploy, không có API sửa. Design có bảng nháp riêng |
| `workflow_call_reference`, `workflow_event_reference` | public | Flow Engine tính ra khi nhận deploy |
| `workflow_active_version` | public | Di sản từ trước bản draft/publish. Xử lý theo spec draft-publish |
| `ACT_*`, `agent_*`, `auth_*`, `platform_*`, `service_callback_wait`, `workflow_service_*`, `workflow_tenant`, `workflow_operation_audit`, `workflow_assignment_fallback_command` | public | |

Bảng mới ở flow_admin: lịch sử phát hành lưu nguyên JSON đã gửi, dùng cho rollback và mở lại vào bản nháp. `work_cluster` lưu danh sách Flow Engine, URL và cách xác thực.

Bảng mới ở public:

- `flw_se_deployment`, `flw_se_deployment_resource`, `flw_se_definition`: service engine chép theo `FLW_SE_*` của Flowable Enterprise, xem mục Service engine.
- `work_service_environment`: base URL và credential theo dịch vụ, xem mục Cấu hình môi trường.

Bảng chép theo Flowable dùng đúng tên bảng và tên cột của Flowable (`flw_se_*`, cột có dấu `_` ở cuối), viết thường theo thói quen Postgres. Bảng không có bản tương ứng trong Flowable (như `work_service_environment`) dùng tiền tố `work_`, theo tên khu vực Work.

### Service engine

Flowable 8.0.0 mã nguồn mở không có service engine (`FLW_SE_*` chỉ có ở bản Enterprise), nên Flow Engine tự dựng theo đúng tên bảng, tên cột và cách liên kết của Flowable. Cách làm chép theo Event Registry mã nguồn mở (engine dựng cùng khuôn), đọc từ mã nguồn Flowable 8.0.0. Chi tiết và nguồn từng điểm: `flow-engine/docs/superpowers/plans/2026-11-13-service-engine.md`. Đã làm ở V41.

| Bảng | Cột | Vai trò |
|---|---|---|
| `flw_se_deployment` | `id_`, `name_`, `key_`, `category_`, `deploy_time_`, `tenant_id_`, `parent_deployment_id_` | Một dòng cho mỗi lần deploy dịch vụ |
| `flw_se_deployment_resource` | `id_`, `name_`, `deployment_id_`, `resource_bytes_` | Nội dung JSON của dịch vụ (`<serviceKey>.service`, cùng đuôi file với Flowable). Chỉ phần thiết kế, không chứa URL hay secret |
| `flw_se_definition` | `id_`, `rev_`, `key_`, `version_`, `deployment_id_`, `tenant_id_`, `name_`, `resource_name_` | Định nghĩa dịch vụ theo version. `version_` tăng theo `(key_, tenant_id_)`. Duy nhất trên `(key_, version_, tenant_id_)` |

Kiểu cột, `tenant_id_` mặc định `''`, khoá ngoại và index theo `FLW_EVENT_*`. Không có RLS, giống `ACT_*` và `FLW_EVENT_*`; tenant lọc trong query. Không có hai bảng Liquibase, vì migration dùng Flyway.

**Deploy.** File `<serviceKey>.service` của mọi dịch vụ BPMN gọi tới nằm **ngay trong deployment BPMN**. `ServiceDeployer` (một `EngineDeployer` gắn vào process engine, như `EventDeployer` của Flowable) tách chúng ra thành một `flw_se_deployment` con với `parent_deployment_id_ = id deployment BPMN`, cùng tenant, trong cùng giao dịch. Xoá deployment BPMN thì xoá luôn deployment con. Deployment BPMN tự lấy chính `id` của nó làm `PARENT_DEPLOYMENT_ID_` (hành vi có sẵn của Flowable), nên không cần `publishId` làm cha. Rollback về vK deploy lại toàn bộ resource của vK, kể cả `.service`. Phát hành lại một quy trình có BPMN không đổi vẫn được, nếu dịch vụ của nó đã được publish lại.

**Tra định nghĩa lúc chạy.** Giống `GetEventModelCmd` của Flowable: service task lấy deployment cha của process definition (`ProcessDefinitionUtil.getDefinitionDeploymentId`), tìm `flw_se_definition` theo `key_` trong deployment con của cha đó. Không thấy thì lấy bản mới nhất theo `(key, tenant)`, rồi tới tenant mặc định nếu bật. Service task có `sameDeployment="false"` thì luôn lấy bản mới nhất, giống task DMN. Model được cache theo definition id (`workflow.service-registry.definition-cache-limit`, mặc định 200).

**Ghép với môi trường.** Lúc gọi, phần thiết kế lấy từ `flw_se_definition`, còn base URL và credential lấy từ cấu hình hiện tại của dịch vụ. Tạm thời đó là bản publish mới nhất trong catalog; sau này là `work_service_environment`.

Nhờ vậy instance luôn gọi dịch vụ đúng như lúc version quy trình của nó được deploy. Publish lại dịch vụ chỉ có hiệu lực cho lần phát hành quy trình sau.

**Không migrate dữ liệu** (người dùng chốt 2026-11-13). admin-be-migrations tạo schema `flow_admin` với bảng rỗng. Bản nháp, catalog, event và luật gán được soạn lại rồi publish lại từ đầu. Các bảng soạn thảo cũ ở `public` không còn ai dùng sau giai đoạn 2, và được Flow Engine xoá bằng một migration ở bản phát hành sau đó.

## Deployment API

Không có định dạng package, manifest hay artifact dùng chung. Flow Admin BE gọi Flow Engine bằng request JSON thường.

### Deploy quy trình

`POST /api/deployment/processes`

```json
{
  "publishId": "01JC…",
  "tenantId": "…",
  "processKey": "order-approval",
  "bpmnXml": "<definitions …>",
  "services": [ { "serviceKey": "crm", "operations": [ … ] } ],
  "assignments": [ { "nodeId": "approve", "targets": [ … ] } ]
}
```

- `services`: cấu trúc operation, tham số, luật lỗi của các dịch vụ mà Service Task dùng. Không chứa URL hay secret.
- `assignments`: luật gán theo node id; đích nên là role/nhóm.
- Event/channel và quy trình con (call activity) **không** đi kèm, chỉ tham chiếu theo key trong BPMN.
- Trả về `{ deploymentId, processDefinitionId, version }`.

### Deploy event

`POST /api/deployment/events` với `{ publishId, tenantId, events: [...], channels: [...] }`. Event/channel dùng chung được publish riêng, đúng cách Flowable khuyên, để publish một quy trình không bao giờ đổi được event mà quy trình khác đang dùng.

### Quy tắc

- **Idempotent theo `publishId`.** `publishId` được ghi làm `key` của deployment Flowable (`ACT_RE_DEPLOYMENT.KEY_`). Gửi lại cùng `publishId` thì Flow Engine tìm thấy deployment cũ và trả lại kết quả cũ, không tạo version mới. Nhờ vậy Flow Admin BE gửi lại được an toàn khi mạng lỗi.
- **Một giao dịch.** Với quy trình: deploy BPMN, deploy dịch vụ vào `flw_se_*` với cùng deployment cha, gắn luật gán người, tính lại `workflow_call_reference`/`workflow_event_reference`. Với event: deploy event/channel. Một bước lỗi thì không có gì được ghi.
- **Tham chiếu phải có sẵn.** Quy trình tham chiếu event hay quy trình con chưa được deploy thì bị từ chối kèm danh sách key còn thiếu. Vì vậy phải publish event trước quy trình dùng nó.
- **Chỉ thêm, không phá.** Muốn đổi API thì chỉ thêm trường mới (có giá trị mặc định); không xoá hay đổi nghĩa trường cũ.
- **Event: bản sau thắng, giống Flowable.** Publish event cùng key với nội dung khác thì tạo version mới, và mọi quy trình dùng key đó đều theo version mới. Trước khi gửi, Design hiện danh sách quy trình đang tham chiếu key này để người publish biết. Muốn đổi theo kiểu không tương thích thì nên tạo key mới.
- **Event trùng nội dung không deploy lại.** Event/channel có hash giống bản đang chạy thì bỏ qua, để không tăng version event một cách vô ích.
- **Luật gán người cố định theo version.** Luật đi cùng request deploy và gắn với definition id. Sau khi deploy không sửa được. Muốn đổi thì publish version mới. Thay người phụ trách thì đổi thành viên role/nhóm. Xử lý gấp một task cụ thể thì admin giao lại task đó qua Hub. Đây đúng là cách Flowable làm.
- **Dịch vụ phải có cấu hình môi trường.** Mọi dịch vụ trong request phải đã có `work_service_environment` ở Flow Engine đó. Thiếu thì từ chối và trả danh sách dịch vụ còn thiếu, để người dùng sang Hub cấu hình rồi publish lại.

## Luồng phát hành

```
Designer bấm Phát hành (chọn đích Flow Engine)
  │
  ▼ Flow Admin BE
  1. Chạy guard trên bản nháp
  2. Dựng request: BPMN + các dịch vụ đang được tham chiếu + luật gán
     (event không đi kèm; event được publish riêng qua /api/deployment/events)
  3. Ghi lịch sử phát hành (JSON request, publishId) ở trạng thái PENDING
  4. POST /api/deployment/processes  (JSON, service token)
  │
  ▼ Flow Engine
  5. Chạy lại guard
  6. Một giao dịch: deploy BPMN và dịch vụ (cùng deployment cha), ghi luật, reference
  7. Trả về { deploymentId, processDefinitionId, version }
  │
  ▼ Flow Admin BE
  8. Lịch sử phát hành chuyển sang DEPLOYED kèm version. Lỗi thì FAILED kèm lý do từ Flow Engine
```

Các thao tác đã có trong spec draft-publish (`2026-11-05-draft-publish-design.md`) đổi như sau:

| Thao tác | Trước đây | Sau khi tách |
|---|---|---|
| Phát hành | Deploy ngay trong cùng process | POST JSON sang Flow Engine |
| Ngừng / Phát hành lại | Gọi engine trực tiếp | `POST /api/deployment/processes/{key}/suspend` hoặc `.../resume` |
| Quay về vK | Deploy lại XML của vK | Gửi lại JSON đã lưu của vK với `publishId` mới. Flow Engine tạo vN+1 |
| Xoá version | Gọi engine trực tiếp | `DELETE /api/deployment/processes/{key}/versions/{v}`. Flow Engine giữ các điều kiện chặn như cũ |
| Mở vK vào bản nháp, huỷ thay đổi | Đọc từ engine | Đọc từ lịch sử phát hành ở flow_admin, không cần gọi Flow Engine |
| "Có thay đổi chưa phát hành" | So hash nháp với latest | So hash nháp với lần phát hành gần nhất đã DEPLOYED lên đích đang chọn |

## Cấu hình môi trường ở Flow Engine

Phần này bám nguyên tắc "URL, xác thực và bí mật không nằm trên sơ đồ" ở spec `2026-11-02`.

- `work_service_environment(tenant_id, service_key, base_url, auth_config, secret_cipher, updated_by, updated_at)`. Bảng này nằm ở Flow Engine, secret được mã hoá bằng master key của Flow Engine.
- Lúc chạy, Flow Engine ghép định nghĩa dịch vụ (`flw_se_definition` theo deployment cha) với cấu hình môi trường hiện tại để có request hoàn chỉnh. Đổi URL hay secret có hiệu lực ngay, không cần publish lại. Đây là hành vi mong muốn, vì việc xoay secret là việc vận hành.
- Hub sửa bảng này qua Admin API. Design không bao giờ thấy secret.
- Channel event có giá trị phụ thuộc môi trường cũng dùng cùng cơ chế.
- Hiện nay `service_operation_draft` đang giữ secret. Vì không migrate dữ liệu, giá trị cấu hình môi trường được nhập mới vào `work_service_environment` (qua Admin API, sau này qua Hub). Bảng nháp mới ở flow_admin không có cột secret.

## API của Flow Engine

Có ba nhóm API, mỗi nhóm một đối tượng gọi và một cách xác thực:

| Nhóm | Ai gọi | Xác thực | Ví dụ |
|---|---|---|---|
| `/api/runtime/**` | Khu vực Work của Flow Admin, hệ thống ngoài | Keycloak JWT của người dùng, hoặc service token cho hệ thống | start, task approve/reject/transfer, interaction, hộp việc |
| `/api/deployment/**` | Flow Admin BE | Service token (`workflow_service_client`, giới hạn `allowed_paths`), header `X-Acting-User` để ghi audit | deploy quy trình/event, suspend/resume, xoá version, kiểm key có tồn tại |
| `/api/admin/**` | Flow Admin BE (Hub) | Chuyển tiếp Keycloak JWT của người dùng ("Current User"), để role và RLS của Flow Engine tự áp dụng | instance page/detail/terminate, job lỗi/retry, deploy, tenant/user/role, cấu hình môi trường, outbox, audit |

Phải dùng token người dùng cho Admin API để không có "confused deputy": Hub không được làm điều mà chính người dùng đó không được phép làm trên Flow Engine. Flow Engine hiện đã xác minh được Keycloak JWT (`KeycloakJwtVerifier`), nên không cần cơ chế mới.

Các endpoint đang có (`/workflow/management/**`, `/workflow/**`) vẫn chạy song song cho tới khi frontend chuyển xong ở giai đoạn 4, rồi mới bỏ.

## Xác thực và phân quyền

- **Đăng nhập:** Flow Admin đăng nhập Keycloak SSO một lần. Cùng một access token được gửi tới cả hai backend, và mỗi backend tự xác minh token (kiểm `aud` hoặc `azp` thuộc danh sách client được phép).
- **Role:** lấy từ `eim_roles` và sitemap EIM như hiện nay, vẫn một mã ứng dụng EIM. Sitemap chia nhánh theo ba khu vực Design, Hub, Work. Mỗi backend chỉ chấp nhận các role dành cho phần của nó, nên quyền Design và quyền Work vẫn tách nhau dù dùng chung một frontend.
- **Tenant:** danh sách tenant và membership nằm ở Flow Engine. Flow Admin BE đọc qua Admin API khi cần, ví dụ để chọn tenant lúc soạn, và cache ngắn hạn.
- **Quyền trong Design** (tạo, sửa, xoá, publish, mở khoá, theo đích) giống Design access control của Flowable. Làm ở giai đoạn 5.

## Frontend: `flow-admin`

Một repo `flow-admin`, lấy từ `workflow-agent-web` hiện tại. Đây là một SPA Vue duy nhất, chia thành ba khu vực:

```
flow-admin/src/
  areas/
    design/   bpmn/, process-definition (designer), service-registry, event-registry,
              assignment-rule                                  → gọi /api/admin-be
    hub/      instances, jobs, deployments, clusters, environment, access, tenant
                                                               → gọi /api/admin-be (BFF)
    work/     inbox (mới), start process, quy trình của tôi, chi tiết task
                                                               → gọi /api/engine
  shared/     components, styles, http, auth, layout, router guard, i18n
```

Quy tắc ranh giới, kiểm bằng `__tests__/architecture.spec.ts` (đã có sẵn):

- Các khu vực không import lẫn nhau. Chỉ được import từ `shared/`.
- Mỗi khu vực có một API client riêng, trỏ tới đúng một backend. Khu vực Work không được dùng client của Flow Admin BE, và ngược lại.
- Route có tiền tố theo khu vực (`/design/**`, `/hub/**`, `/work/**`). Menu lấy từ sitemap EIM.
- Khu vực Work chỉ dùng Runtime API. Nhờ vậy sửa frontend cho Design hay Hub không thể làm hỏng luồng của người dùng cuối, trừ khi đụng vào `shared/`.

Các màn đang có chuyển như sau:

- `process-instances` hiện nay là màn vận hành, nên chuyển sang Hub.
- Các thao tác duyệt, từ chối, chuyển trong màn chi tiết instance được dùng lại cho khu vực Work.

## Triển khai

Ba image, ba đơn vị deploy. Số node tối thiểu theo bảng thành phần là 2 cho mỗi thành phần:

| Repo | Image | Cổng local | Số node tối thiểu | Phụ thuộc lúc chạy |
|---|---|---|---|---|
| `flow-engine` | `flow-engine` | 8080 | 2 | Postgres, Redis, Keycloak |
| `flow-admin-be` | `flow-admin-be` | 8081 | 2 | Postgres, Keycloak, các Flow Engine đích |
| `flow-admin` | `flow-admin` (nginx + static) | 5174 | 2 | `flow-admin-be`, `flow-engine` |

- nginx của Flow Admin proxy `/api/admin-be/**` tới Flow Admin BE và `/api/engine/**` tới Flow Engine. URL của hai backend lấy từ biến môi trường lúc chạy container (template nginx với `envsubst`), không đóng cứng lúc build, để một image dùng được cho mọi môi trường.
- Flow Engine chạy nhiều node như hiện nay: DB làm điểm đồng bộ, Redis giữ session.
- Flow Admin là static, chạy nhiều node không cần gì thêm.
- Flow Admin BE chạy 2 node thì cần Redis giữ session và cần chốt cơ chế cho việc chạy nền (gửi lại deploy lỗi mạng), xem mục Rủi ro.
- Postgres theo mô hình Master-Slave. Cả hai backend ghi vào master. Có cho đọc từ slave hay không thì chưa chốt, xem mục Rủi ro.

### Chạy local

- `flow-engine` có `compose.yaml` riêng, gồm Postgres, Redis và chính nó. Compose này là "môi trường nền" cho hai repo còn lại.
- `flow-admin-be` chạy bằng Maven, trỏ tới Postgres của compose trên.
- `flow-admin` chạy `vite` với hai proxy, một tới Flow Admin BE, một tới Flow Engine. Mỗi backend có thể là bản local hoặc bản đang chạy trên dev.

## Giữ Flow Engine ổn định khi deploy riêng

Spec này không cấu hình CI/CD. Dù build bằng công cụ gì, mỗi repo phải giữ các chốt chặn sau:

- **API chỉ thêm, không phá.** Deployment API và Admin API chỉ được thêm trường hoặc endpoint; không xoá, đổi tên hay đổi nghĩa thứ đang có.
- **Thứ tự deploy.** Flow Engine phải chạy được với Flow Admin BE bản cũ. Khi thêm trường mới thì deploy Flow Engine trước, rồi mới deploy Flow Admin BE và Flow Admin.
- **Migration chỉ thêm, không phá.** Migration DB của mỗi repo không được xoá hay đổi tên cột đang dùng trong cùng một lần phát hành.

## Lộ trình

Thứ tự này nhằm **làm phía Flow Engine trước rồi đóng băng**. Sau giai đoạn 3, công việc thường ngày chỉ còn ở `flow-admin-be` và hai khu vực Design, Hub của `flow-admin`.

**Giai đoạn 0 (đã làm, 2026-11-13):** dựng 3 repo cạnh repo cũ `workflow-agent`, không giữ lịch sử git. Cùng ngày đổi tên repo, artifact và app theo bảng thành phần.

- `flow-engine` = toàn bộ backend hiện tại, cộng thêm `compose.yaml`, `Dockerfile` và docs.
- `flow-admin` = toàn bộ frontend hiện tại.
- `flow-admin-be` = khung gồm `shared-kernel` và `admin-be-boot`.

| Giai đoạn | Nội dung | Xong khi |
|---|---|---|
| 1. Phía nhận deploy của Flow Engine | Trong `flow-engine`: service engine `flw_se_deployment`, `flw_se_deployment_resource`, `flw_se_definition`; `work_service_environment`; `/api/deployment/processes` và `/api/deployment/events` (JSON); runtime tra định nghĩa dịch vụ theo deployment cha thay vì `catalog.publishedService(key)`; deploy event tách khỏi deploy quy trình; luật gán người chỉ ghi khi nhận deploy (bỏ các endpoint sửa luật ở `/workflow/node-assignment-rule`). Nút Phát hành gọi cùng service đó (gọi nội bộ) | Phát hành lại một dịch vụ không làm đổi hành vi của instance đang chạy. Publish một quy trình không đổi được event. Có test chứng minh |
| 2. Chuyển code soạn thảo sang Flow Admin BE | Chuyển code bản nháp, catalog nháp, event nháp, luật gán nháp từ `flow-engine` sang `flow-admin-be`; tạo schema `flow_admin` (bảng rỗng, không chuyển dữ liệu) cùng user DB riêng; nút Phát hành gọi REST. Trong `flow-admin`: tách hai API client, `/api/admin-be` và `/api/engine` | Hai backend chạy riêng. Tắt Flow Admin BE thì Flow Engine vẫn đi đủ luồng; test tích hợp chạy với hai user DB tách quyền |
| 3. Admin API và Hub | `/api/admin/**`; `work_cluster`; khu vực Hub trong `flow-admin` (gọi qua BFF của Flow Admin BE): instance, giao lại task, job lỗi, deploy, cấu hình môi trường, tenant/access | Không còn màn vận hành nào gọi thẳng `/workflow/management/**`. **Đóng băng Flow Engine** |
| 4. Chia khu vực frontend | Sắp xếp lại `src/` thành `areas/{design,hub,work}` và `shared/`; luật ranh giới trong `architecture.spec.ts`; khu vực Work có hộp việc; bỏ các endpoint cũ | Ba khu vực không import lẫn nhau; khu vực Work chỉ gọi Runtime API |
| 5. Hoàn thiện phát hành | Nhiều đích Flow Engine; bắt duyệt trước khi publish; quyền theo đích trong Design | Publish lên prod buộc phải qua duyệt |

## Quyết định đã chốt (2026-11-13)

Ba điểm đầu theo cách Flowable Enterprise đang làm, đã đối chiếu tài liệu versioning-deployment, user task, events/channels và bản 2026.1.

1. **Luật gán người đi cùng request deploy, cố định theo version.** Bỏ tính năng sửa luật của version đã deploy. Thay người thì đổi thành viên role/nhóm. Xử lý gấp thì giao lại từng task qua Hub.
2. **Event/channel publish riêng (`/api/deployment/events`).** Deploy quy trình chỉ tham chiếu event key. Publish event cùng key thì bản sau thắng, kèm cảnh báo ở Design về các quy trình đang dùng.
3. **Không migrate dữ liệu.** Không tạo định nghĩa dịch vụ "di sản" cho instance cũ, không chuyển dữ liệu soạn thảo sang `flow_admin`. Hệ thống mới bắt đầu với dữ liệu rỗng, rồi soạn và publish lại. Instance và deploy cũ trên môi trường hiện tại không được hỗ trợ sau khi lên giai đoạn 1.
4. **Đặt tên theo bảng thành phần.** Repo, artifact Maven, image, tên app Spring, schema, user DB và tiền tố API dùng tên Flow Admin, Flow Admin BE, Flow Engine. Java package giữ nguyên. Tên bảng: bảng chép theo Flowable dùng tên Flowable (`flw_se_*`), bảng riêng dùng tiền tố `work_`.
5. **Khu vực Work nằm trong Flow Admin.** Bảng thành phần không có giao diện cho người dùng cuối. Hộp việc, start, duyệt vẫn là một khu vực của Flow Admin.
6. **Không có hợp đồng package (người dùng chốt 2026-11-13, "làm đơn giản").** Bỏ zip, manifest, hash, `contractVersion`, `fixtures/`, artifact `app-contract` và bảng `work_app_deployment` (dùng `ACT_RE_DEPLOYMENT` của Flowable). Hai backend chỉ nói chuyện qua REST/JSON, mỗi bên tự giữ DTO.

## Rủi ro và việc cần kiểm

- **Lệch với bảng thành phần, chưa chốt:**
  - Bảng ghi Flow Admin dùng ReactJS, còn `flow-admin` đang dùng Vue. Nếu React là bắt buộc thì nên viết lại ở giai đoạn 4, vì giai đoạn đó vốn đã sắp xếp lại toàn bộ `src/`.
  - Flow Admin BE chạy tối thiểu 2 node: cần Redis cho session, và cần khoá phân tán hoặc outbox cho việc chạy nền để hai node không gửi trùng.
  - Postgres Master-Slave: cần chốt có tách đọc sang slave không. Nếu có, chỉ màn danh sách của Hub được đọc slave. Nhận deploy và runtime luôn dùng master.
- **Kiểm điều kiện trên DB dùng chung:** theo memory, các truy vấn kiểm điều kiện của V40 mới chỉ chạy trên local. Phải chạy lại trên môi trường dùng chung trước khi phát hành.
- **Dọn dữ liệu cũ:** vì không migrate, trước khi lên giai đoạn 1 ở môi trường dùng chung phải xoá sạch instance và deploy cũ, hoặc dựng DB mới, để không còn instance nào đọc `service_publication`.
- **Phối hợp giữa các repo:** một tính năng cần cả Flow Engine lẫn Design (ví dụ một loại node mới) phải đi theo thứ tự: phát hành Flow Engine trước, sau đó Flow Admin BE dùng trường/endpoint mới. Không có kiểm tự động giữa hai repo, nên DTO hai bên có thể lệch; cần test tích hợp gọi Flow Engine thật.
- **Frontend chung deploy chung:** mỗi lần deploy Flow Admin, kể cả khi chỉ sửa cho Design, cũng là deploy lại màn của người dùng cuối. Muốn giảm rủi ro thì giữ `shared/` ít thay đổi, và trước khi deploy phải chạy smoke test e2e (Playwright, đã có sẵn) cho các luồng chính của khu vực Work.
- **Docs:** spec kiến trúc tổng (file này) nằm ở `F:\flowable\`, ngoài cả 3 repo, vì nó áp dụng cho cả ba. Spec cũ trong `docs/superpowers/` nằm ở `flow-engine`. Từ giai đoạn 2, spec của tính năng nằm ở repo chứa phần lớn thay đổi. Spec chạm tới Deployment API hay Admin API thì luôn nằm ở `flow-engine`.
- **Kích thước:** đặt giới hạn cỡ request ở Deployment API (cấu hình Spring là đủ).
