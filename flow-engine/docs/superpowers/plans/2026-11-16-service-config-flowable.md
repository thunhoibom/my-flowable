# Cấu hình dịch vụ theo môi trường: làm theo Flowable

Spec gốc: `F:\flowable\2026-11-13-flow-admin-engine-architecture-design.md`. Thay cho màn Hub "Cấu hình môi trường"
(`work_service_environment`, V42) và `ServiceEnvironmentSync` của Flow Admin BE.

**Xong khi:** model dịch vụ mang base URL và cách xác thực (chỉ tham chiếu, không có secret); giá trị theo môi trường
nằm ở ba mục Hub của Flow Engine: Secret, OAuth2 client, Biến tenant; không còn `work_service_environment`.

**Lưu ý:** sau giai đoạn 3 Flow Engine đã đóng băng. Plan này đổi Flow Engine có chủ ý, nên coi là một lần nâng phiên bản
Flow Engine. Hệ thống chỉ chạy local, chưa deploy (không giữ tương thích ngược, không chuyển dữ liệu).

## Trạng thái (2026-11-16): đã làm

Người dùng duyệt cả 8 đề xuất ở mục "Quyết định". Khác plan ở mấy điểm nhỏ:

- `work_oauth2_client` không có `issuer_uri`: token URI nhập thẳng, không dò từ issuer (đỡ một đường gọi mạng).
  Client secret bắt buộc khi tạo; client id để trống thì lấy registration key (như Flowable).
- Editor ở Design đọc danh sách tên qua `deployed` (Admin API của Flow Engine, chỉ đọc) ngay từ frontend, không thêm hàm
  vào `FlowEngineClient`.
- Tên biến tenant phải là identifier (`[A-Za-z_][A-Za-z0-9_]*`) để dùng được trong `${tenant.<tên>}`.
- `${tenant.x}` là một `ELResolver` đặt trước resolver mặc định của Flowable (`setPreDefaultELResolvers`), nên dùng được
  ở mọi biểu thức (input mapping, base URL, khoá) và che biến quy trình tên `tenant`. Biến không có thì biểu thức lỗi.
- Thiếu secret/client lúc gọi là lỗi kỹ thuật (job retry, rồi vào Job lỗi), không phải BPMN error. Token endpoint không
  tới được thì `SERVICE_UNAVAILABLE`, như dịch vụ không tới được.
- Bỏ luôn cấu hình `flow-admin.engine.environment` / `WORKFLOW_SERVICES_ENVIRONMENT` (chỉ dùng để chọn URL theo môi trường)
  và `flow-admin.service-secrets.master-key-base64` của Flow Admin BE.

Kiểm tra: `mvn verify` của `flow-engine` và `flow-admin-be` qua (Testcontainers chạy thật), trừ luật branch coverage của
`service-registry` (0.60 < 0.70, đã hỏng từ trước ở 0.57). Frontend: type-check, lint, 182 unit test, build qua; e2e còn 2
lỗi có từ trước (axe color-contrast, nút "Lưu phiên bản mới" của designer).

Chạy script trên DB local: `flow-engine/db` V44 → V47, rồi `flow-admin-be/db/V1`. Sau đó publish lại các dịch vụ (V1 xoá
snapshot cũ), tạo secret / OAuth2 client / biến tenant ở Hub, rồi publish lại các quy trình dùng dịch vụ.

## Flowable làm thế nào (đã đối chiếu tài liệu, 2026-11-16)

| Chỗ | Flowable | Nguồn |
|---|---|---|
| Model dịch vụ REST (Design) | Có **Base URL**, "can be an expression or have an expression". **Authorization** đặt ở model, từng operation ghi đè được | develop/be/service-registry; reactmodel/.../configuration-examples |
| Kiểu xác thực REST | Basic, Bearer; API Key gửi `X-API-KEY` (2025.2.05+); Custom Header (2025.2.05+) | configuration-examples |
| Nguồn giá trị của khoá | API Key: "provided directly, through an expression, or resolved from a secret". Custom Header: thêm "obtained from an OAuth2 client" | configuration-examples |
| Secret (Hub) | Lưu theo tên trong môi trường; giá trị "shown only once, at creation time"; đổi thì "delete it and create it again"; model tham chiếu theo tên; quyền `hub:configuration:secret` | user/hub/configuration/secrets |
| OAuth2 client (Hub) | Registration Key (model tham chiếu theo key), Client ID/Secret, Issuer URI/Token URI, Scopes, Grant type: client credentials, JWT bearer, token exchange | user/hub/configuration/oauth2-clients |
| Biến tenant (Hub) | "key/value settings scoped to the tenant", dùng cho "service endpoints, feature flags or environment-specific constants"; cờ protected (che giá trị, bật rồi không tắt được, lưu mã hoá bằng `flowable.platform.protected-variable-encryption.*`); trong biểu thức: `${tenant.someUrl}` | user/hub/configuration/tenant-variables; admin/installs/platform-full/step-by-step-installation |
| Ghi đè bằng property của server | `flowable.service-registry.rest-settings.<serviceKey \| default>.baseUrl` (và `url`, `method`, `headers`, `timeout`, `ignoreErrors`, `noRedirects`...). Thứ tự: dịch vụ → `default` → operation → model. Không có property cho xác thực | develop/be/service-registry, "Global Settings for REST Services" |
| Hub có deploy không | Không; Hub chỉ xem/xoá deployment, publish đi từ Design | user/hub/maintenance/deployments |

Tài liệu **không nói**: cú pháp dùng secret trong biểu thức, secret/OAuth2 client có theo tenant không, Flowable lưu chúng
ở bảng nào, có chặn deploy khi thiếu secret không. Plan tự chốt các điểm này (mục "Quyết định").

## Hiện tại ở đây

- Catalog Design (`service_definition`, `service_operation_draft`) giữ base URL **theo từng môi trường**
  (`base_urls_json`), shared auth và auth từng operation kèm **secret đã mã hoá** (`ServiceSecretCipher` ở Flow Admin BE).
- Publish quy trình: `.service` trong deployment bị `ServiceModelJson.designOnly` bỏ URL và khoá; chỉ giữ `authMode`.
- Lần publish đầu, `ServiceEnvironmentSync.seed` chép URL + khoá sang `work_service_environment` của Flow Engine;
  sau đó Hub "Cấu hình môi trường" giữ.
- Lúc gọi: `PublishedServiceInvoker` đọc `work_service_environment` (`ServiceSettingsSource`) mỗi lần gọi.
- Deploy bị chặn nếu dịch vụ chưa có dòng `work_service_environment` (`ProcessDeploymentServiceImpl.deploy`).
- Kiểu xác thực: NONE, BEARER, BASIC, API_KEY (header/query), OAUTH2_CLIENT, MTLS, BEARER_VARIABLE, HMAC, AWS_SIG_V4.
- `ServiceRegistryDelegate` đã tính biểu thức input bằng `ExpressionManager` của Flowable trên execution.

## Thiết kế mới

### Model dịch vụ (Design, đi trong `.service`)

```json
{
  "serviceKey": "crm",
  "baseUrl": "${tenant.crmBaseUrl}/api",
  "authorization": { "type": "BEARER", "token": { "source": "OAUTH2_CLIENT", "value": "crm-oauth" } },
  "operations": [
    { "operationKey": "createLead", "authMode": "INHERIT", "authorization": null, "...": "giữ như cũ" },
    { "operationKey": "ping", "authMode": "NONE" },
    { "operationKey": "export", "authMode": "OVERRIDE",
      "authorization": { "type": "API_KEY", "header": "X-API-KEY", "key": { "source": "SECRET", "value": "crmExportKey" } } }
  ]
}
```

- `baseUrl` một giá trị, có thể chứa biểu thức (bỏ danh sách URL theo môi trường).
- Giá trị khoá: `{ "source": "VALUE | EXPRESSION | SECRET | OAUTH2_CLIENT", "value": "..." }`.
- `authMode` giữ như cũ: INHERIT (dùng của model), OVERRIDE (của operation), NONE.

| Kiểu | Trường | Nguồn được phép | So với Flowable |
|---|---|---|---|
| NONE | | | |
| BASIC | `username`, `password` | username: VALUE/EXPRESSION/SECRET; password: SECRET/EXPRESSION | như Flowable |
| BEARER | `token` | SECRET/EXPRESSION/OAUTH2_CLIENT | Flowable lấy OAuth2 qua Custom Header; ở đây cho cả Bearer |
| API_KEY | `header` (mặc định `X-API-KEY`), `location` HEADER/QUERY, `key` | SECRET/EXPRESSION | Flowable cố định header `X-API-KEY`; ở đây giữ tuỳ chọn header/query đang có |
| CUSTOM_HEADER | `name`, `value` | VALUE/EXPRESSION/SECRET/OAUTH2_CLIENT | **mới**, như Flowable |
| MTLS | `certificatePem` (VALUE), `privateKey` | privateKey: SECRET | mở rộng riêng, giữ |
| HMAC | `header`, `key` | key: SECRET | mở rộng riêng, giữ |
| AWS_SIG_V4 | `accessKey`, `secretKey`, `region`, `service` | accessKey: VALUE/SECRET; secretKey: SECRET | mở rộng riêng, giữ |

Bỏ: `OAUTH2_CLIENT` (thành BEARER với nguồn OAUTH2_CLIENT), `BEARER_VARIABLE` (thành BEARER với EXPRESSION `${tenVariable}`).

### Ba mục Hub trên Flow Engine (giá trị theo môi trường)

Bảng mới (script tay `flow-engine/db`), đều có `tenant_id` và RLS giống V42; giá trị bí mật mã hoá bằng
`workflow.service.secrets.master-key-base64` (`ServiceSecretCipher` hiện có):

- `work_secret (tenant_id, name, value_cipher, created_by, created_at)`, khoá `(tenant_id, name)`.
- `work_oauth2_client (tenant_id, registration_key, client_id, client_secret_cipher, issuer_uri, token_uri, scopes,
  grant_type, updated_by, updated_at)`, khoá `(tenant_id, registration_key)`.
- `work_tenant_variable (tenant_id, name, value, value_cipher, protected, updated_by, updated_at)`, khoá `(tenant_id, name)`.
  Protected: `value` null, giá trị ở `value_cipher`; bật rồi không tắt.

Admin API (role MANAGE: `WF_PLATFORM_ADMIN`, `WF_ADMIN`; audit mọi thay đổi):

| Endpoint | Ghi chú |
|---|---|
| `GET /api/admin/secrets` | chỉ tên, người tạo, lúc tạo |
| `POST /api/admin/secrets` `{name, value}` | trùng tên → 409. Không có PUT: đổi = xoá rồi tạo lại |
| `DELETE /api/admin/secrets/{name}` | |
| `GET/POST /api/admin/oauth2-clients`, `PUT/DELETE .../{key}` | client secret không bao giờ trả về; để trống khi PUT thì giữ |
| `GET/POST /api/admin/tenant-variables`, `PUT/DELETE .../{name}` | protected trả `******`; không cho tắt protected |

### Lúc gọi dịch vụ (Flow Engine)

1. **Base URL**: property `workflow.service-registry.rest-settings.<serviceKey>.base-url` → `...default.base-url` →
   `baseUrl` của model. Tính biểu thức trên execution. Operation có URL tuyệt đối thì bỏ qua base URL (như hiện nay).
   Cùng chỗ: `...<serviceKey | default>.timeout` ghi đè read timeout.
2. **Xác thực**: theo `authMode` chọn authorization của model hoặc operation.
3. **Giá trị khoá**: VALUE dùng nguyên; EXPRESSION tính trên execution; SECRET đọc `work_secret` theo tenant của
   execution; OAUTH2_CLIENT lấy token từ `work_oauth2_client` (client credentials, cache token như
   `OauthClientCredentials` hiện có, khoá cache theo tenant + registration key).
4. **`${tenant.x}`**: thêm ELResolver cho tên `tenant` vào `ExpressionManager` của process engine; trả biến tenant của
   tenant đang chạy (protected cũng dùng được, giá trị đã giải mã chỉ nằm trong bộ nhớ).
5. Biểu thức cần execution, nên `ServiceRegistryDelegate` đưa một bộ tính biểu thức (`String → String`) xuống
   `ServiceRegistryGateway`/`PublishedServiceInvoker` thay cho việc đọc `ServiceSettingsSource`.

### Lúc deploy (Flow Engine)

Thay kiểm tra "chưa có cấu hình môi trường" bằng kiểm tra tham chiếu trong các `.service` của request: tên secret,
registration key OAuth2, tên `${tenant.x}` (tìm trong `baseUrl` và biểu thức). Thiếu cái nào thì từ chối deploy, liệt kê
đủ. Biểu thức dùng biến quy trình thì không kiểm được, bỏ qua. Ngoài ra từ chối `.service` có khoá bí mật dạng VALUE.

## Quyết định (đề xuất, cần người dùng chốt)

1. **Không cho nhập thẳng giá trị bí mật trong model** (password, token, key, private key chỉ SECRET/EXPRESSION).
   Flowable cho phép "provided directly"; ở đây giữ nguyên tắc Design không thấy secret.
2. **Giữ MTLS, HMAC, AWS SigV4** như phần mở rộng, khoá của chúng là SECRET.
3. **Giữ chặn deploy khi thiếu tham chiếu** (Flowable không ghi có bước này).
4. **Secret không dùng được trong biểu thức** (`${secret.x}` không có): Flowable không ghi cú pháp; tránh secret lọt vào
   biến quy trình qua input mapping. Chỉ dùng qua nguồn SECRET.
5. **Secret, OAuth2 client, biến tenant theo tenant** (RLS), giống mọi bảng `work_*` khác.
6. **OAuth2: chỉ client credentials** ở plan này; JWT bearer / token exchange (on-behalf-of) để sau.
7. **Property ghi đè chỉ `base-url` và `timeout`** (Flowable có thêm `url`, `method`, `headers`... ; thêm khi cần).
8. **Mã menu EIM mới**: `WF_SECRET`, `WF_OAUTH2_CLIENT`, `WF_TENANT_VARIABLE`; bỏ `WF_ENVIRONMENT`. Cần khai trên EIM.

## Các bước

### 1. Flow Engine: ba kho và Admin API

- Script `db/V44__work_secret.sql`, `V45__work_oauth2_client.sql`, `V46__work_tenant_variable.sql` (RLS như V42).
- `service-registry`: `JdbcSecretStore`, `JdbcOauth2ClientStore`, `JdbcTenantVariableStore` (mã hoá bằng `ServiceSecretCipher`).
- `engine-boot`: `SecretController`, `Oauth2ClientController`, `TenantVariableController` dưới `/api/admin/**`, audit
  `SECRET_CREATED/DELETED`, `OAUTH2_CLIENT_SAVED/DELETED`, `TENANT_VARIABLE_SAVED/DELETED`.
- Test: store (Testcontainers, tenant khác không thấy), controller (không trả secret, protected che, không tắt protected,
  secret trùng tên 409).

### 2. Flow Engine: model mới và cách gọi

- `domain`: `CredentialValue(source, value)`, `Authorization` (theo bảng kiểu ở trên), `PublishedService.baseUrl`,
  `PublishedService.authorization`, `PublishedOperation.authorization`. Bỏ `EnvironmentBaseUrl`, `ServiceSettings`,
  `AuthType.OAUTH2_CLIENT`, `AuthType.BEARER_VARIABLE`; thêm `CUSTOM_HEADER`.
- `ServiceModelJson`: không còn `designOnly`; đọc/ghi model đầy đủ.
- `CredentialResolver` (SECRET, OAUTH2_CLIENT, EXPRESSION qua bộ tính biểu thức), `RequestFactory` áp header theo kiểu.
- `TenantVariableELResolver` gắn vào `ExpressionManager` (`ProcessEngineConfigurationConfigurer`).
- `ServiceRegistryDelegate` / `ServiceCallbackService` / `ServiceRegistryGatewayAdapter`: truyền bộ tính biểu thức; bỏ
  chuyện đọc bearer variable riêng.
- Property `workflow.service-registry.rest-settings.<key | default>.base-url | timeout`.
- Deploy: `ServiceDeploymentSupport.missingReferences(tenantId, services)` thay `withoutEnvironment`.
- Xoá: `JdbcServiceEnvironmentStore`, `ServiceEnvironmentController`, `ServiceSettingsSource`; script
  `V47__drop_work_service_environment.sql`.
- Test: thứ tự ghi đè base URL; `${tenant.x}` trong base URL; mỗi nguồn khoá; INHERIT/OVERRIDE/NONE; deploy bị chặn khi
  thiếu secret/client/biến; `.service` có khoá VALUE bị từ chối; IT gọi HTTP thật (server giả) với Bearer từ OAuth2 client.

### 3. Flow Admin BE: catalog

- Bảng catalog (Flow Admin BE sở hữu): script đầu tiên ở `flow-admin-be/db/V1__service_reference_auth.sql`
  (README như `flow-engine/db`): `service_definition` thêm `base_url`, `authorization_json`, bỏ `base_urls_json`,
  `shared_auth_json`; `service_operation_draft` thêm `authorization_json`, bỏ `secret_cipher`, `auth_type`,
  `api_key_header`, `auth_details_json`. Cập nhật bản chép script test ở `design-modeling/src/test/resources`.
- Domain/API catalog theo model mới; kiểm nguồn khoá hợp lệ (quyết định 1).
- Publish: `.service` gửi đủ `baseUrl` + `authorization` (chỉ tham chiếu).
- Xoá `ServiceEnvironmentSync`, `ServiceSecretCipher`, `FlowEngineClient.saveServiceEnvironment/hasServiceEnvironment`.
- `FlowEngineClient`: thêm đọc danh sách tên secret, OAuth2 client, biến tenant (token người dùng) để làm gợi ý cho editor.

### 4. Flow Admin (frontend)

- Hub: bỏ `areas/hub/service-environments`; thêm `secrets`, `oauth2-clients`, `tenant-variables` (route, menu mục 8).
  - Secret: bảng tên + ngày tạo; tạo (giá trị nhập một lần), xoá; không có sửa.
  - OAuth2 client: form key, client id, client secret (để trống giữ), issuer/token URI, scopes, grant type.
  - Biến tenant: tên, giá trị, protected (bật rồi khoá).
- Design, editor dịch vụ: một ô Base URL (gợi ý `${tenant.…}`); khối xác thực ở model và operation, mỗi khoá có chọn
  nguồn (Giá trị / Biểu thức / Secret / OAuth2 client) và chọn tên từ danh sách của Flow Engine (vẫn gõ tay được).
- Test: unit form, e2e tạo secret → dùng trong dịch vụ → publish quy trình → bị chặn khi xoá secret rồi publish lại.

### 5. Tài liệu

- Spec kiến trúc: mục "Tham chiếu Flowable Enterprise" thêm dòng cấu hình dịch vụ; bảng sở hữu bảng thêm ba bảng mới,
  bỏ `work_service_environment`.
- README `flow-engine`, `flow-admin-be`; ghi lại thứ tự chạy script (Flow Engine V44–V47, Flow Admin BE V1).

## Sau khi làm (local)

Dữ liệu cũ không chuyển: chạy script, publish lại các dịch vụ và quy trình đang dùng, nhập lại khoá ở Hub
(deployment cũ không có `baseUrl`/`authorization` trong `.service` nên gọi dịch vụ sẽ lỗi cho tới khi publish lại).

## Nguồn

- https://documentation.flowable.com/latest/develop/be/service-registry
- https://documentation.flowable.com/latest/reactmodel/service-registry/example/configuration-examples
- https://documentation.flowable.com/latest/reactmodel/bpmn/reference/service-registry-task
- https://documentation.flowable.com/latest/user/hub/configuration/secrets
- https://documentation.flowable.com/latest/user/hub/configuration/oauth2-clients
- https://documentation.flowable.com/latest/user/hub/configuration/tenant-variables
- https://documentation.flowable.com/latest/admin/installs/platform-full/step-by-step-installation
- https://documentation.flowable.com/latest/develop/be/be-expressions/app-expressions
- https://documentation.flowable.com/latest/user/hub/maintenance/deployments
