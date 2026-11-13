# Giai đoạn 1: phía nhận deploy của Flow Engine

Spec gốc: `F:\flowable\2026-11-13-flow-admin-engine-architecture-design.md`, mục "Deployment API", "Cấu hình môi trường ở Flow Engine", "Lộ trình". Service engine (`flw_se_*`, V41) làm trước, xem `2026-11-13-service-engine.md`.

**Xong khi:** phát hành lại một dịch vụ không làm đổi hành vi của instance đang chạy; publish một quy trình không đổi được event. Có test chứng minh: `DeploymentApiIntegrationTest` (Postgres thật, toàn bộ app).

## Đã làm

### Cấu hình môi trường: `work_service_environment` (V42)

- Bảng `(tenant_id, service_key)`: `base_url`, `auth_config` (JSON, không có secret), `secret_cipher` (mọi secret của dịch vụ, mã hoá chung bằng `workflow.service.secrets.master-key-base64`), `updated_by`, `updated_at`. Có RLS theo tenant như V38.
- Model `ServiceSettings`: base URL, auth dùng chung, auth riêng từng operation (mode + credential).
- `JdbcServiceEnvironmentStore`: lưu với secret để `null` thì giữ secret cũ; khi đọc danh sách thì bỏ secret, chỉ báo secret nào đang có (`storedSecrets`).
- Runtime (`PublishedServiceInvoker`) đọc qua `ServiceSettingsSource`: dòng `work_service_environment` trước, **tạm thời** không có thì lấy cấu hình trong bản publish mới nhất của catalog (`CatalogServiceSettingsSource`). Nguồn tạm này bỏ ở giai đoạn 2.
- Admin API (vào trước giai đoạn 3 vì cần để nhập cấu hình): `GET /api/admin/service-environments`, `GET|PUT|DELETE /api/admin/service-environments/{serviceKey}`, role `WF_PLATFORM_ADMIN`, `WF_ADMIN`.

### `POST /api/deployment/processes`

```json
{
  "publishId": "01JC…",
  "tenantId": "…",
  "processKey": "order-approval",
  "name": "Duyệt đơn",
  "bpmnXml": "<definitions …>",
  "services": [ { "serviceKey": "crm", "name": "CRM", "operations": [ … ] } ],
  "assignments": [ { "nodeId": "approve", "assignmentType": "CANDIDATE_GROUPS", "candidateGroups": ["managers"] } ]
}
```

Trả về `{ deploymentId, processDefinitionId, processKey, version, suspended }`.

Thứ tự trong một giao dịch (`ProcessDeploymentServiceImpl`):

1. `tenantId` trong body (nếu có) phải khớp tenant của service token.
2. Khoá advisory Postgres theo `(tenant, publishId)`, rồi tìm deployment có `KEY_ = publishId`. Có rồi thì trả lại kết quả cũ; nếu deployment đó thuộc key khác thì `CONFLICT`.
3. Mọi dịch vụ BPMN gọi phải có cấu hình môi trường, thiếu thì `BAD_REQUEST` kèm danh sách.
4. Chạy lại mọi guard (`ProcessDeployer.deployChecked`). Dịch vụ BPMN gọi mà request không gửi thì từ chối kèm danh sách; dịch vụ thừa bị bỏ qua. Nội dung dịch vụ chỉ giữ phần thiết kế (`ServiceModelJson.designOnly`), URL hay secret lỡ gửi kèm đều bị bỏ.
5. Deploy BPMN và các file `<serviceKey>.service` trong một deployment, `KEY_ = publishId`. `ServiceDeployer` tách `.service` thành deployment con `flw_se_*`.
6. Ghi luật gán người cho definition mới (`AssignmentRuleService.create`, có kiểm loại gán theo user task). Luật sai thì cả request bị huỷ.
7. Ghi reference (call, event) và audit `PROCESS_DEFINITION_DEPLOYED`.

Các endpoint đi kèm: `GET /api/deployment/processes/{key}` (kiểm key, version mới nhất), `POST …/{key}/suspend`, `POST …/{key}/resume`, `DELETE …/{key}/versions/{v}`.

### `POST /api/deployment/events`

`{ publishId, tenantId, events: [{ key, name, description, fields }], channels: [{ key, name, description, detection, detectionValue, allowedEventKeys, userIds, groupIds }] }`

- Deploy event trước, rồi channel; channel nhận event chưa deploy thì từ chối.
- Bản sau thắng, giống Flowable. Không chặn thay đổi không tương thích (phía Design cảnh báo).
- Nội dung giống bản đang chạy (so hash với resource Flowable đang lưu) thì không deploy lại. Vì vậy gửi lại an toàn; `publishId` chỉ ghi vào audit.
- Trả về version của từng event/channel và cờ `deployed`.

### Xác thực

- `/api/deployment/**` chỉ cho role `SERVICE` (service token, giới hạn bằng `allowed_paths` của `workflow_service_client`).
- Header `X-Acting-User` thay tên người dùng trong audit; người gọi vẫn là service client.

### Nút Phát hành hiện tại

`ProcessPublishServiceImpl` (phần Design còn trong Flow Engine) gọi cùng `ProcessDeploymentService` với `publishId` mới, dịch vụ lấy từ catalog (hoặc từ deployment vK khi rollback), rồi chép luật gán từ version trước và ghi `workflow_process_release` như cũ. Suspend/resume cũng đi qua service này. Giai đoạn 2 chỉ cần thay lời gọi nội bộ bằng REST.

## Khác với spec (người dùng chốt 2026-11-13)

- **Endpoint sửa luật gán người `/workflow/node-assignment-rule` được giữ** (đánh dấu `@Deprecated`), vì chưa có chỗ lưu luật gán nháp. Bỏ ở giai đoạn 2, khi Design có luật gán nháp ở Flow Admin BE.
- Fallback cấu hình môi trường về catalog, như trên.

## Test

- `JdbcServiceEnvironmentStoreTest`: secret chỉ nằm trong cipher, lưu không kèm secret thì giữ secret cũ, tách theo tenant.
- `PublishedServiceInvokerTest`: cấu hình của Flow Engine thắng catalog; credential riêng của operation.
- `ProcessDeploymentServiceImplTest`: `KEY_ = publishId`, gửi lại trả kết quả cũ, thiếu dịch vụ, thiếu cấu hình môi trường, luật gán, sai tenant, suspend/resume.
- `EventDeploymentServiceImplTest`: trùng nội dung không deploy, bản sau thắng, channel sau event, từ chối channel thiếu event.
- `ServiceDeploymentSupportAdapterTest`: URL và secret gửi kèm bị bỏ khỏi `.service`.
- `ProcessDeploymentControllerTest`: `X-Acting-User`.
- `DeploymentApiIntegrationTest`: hai mục "Xong khi", idempotent theo `publishId`, cấu hình môi trường đổi là có hiệu lực ngay.

## Chưa làm, để giai đoạn sau

- Kiểm thử qua HTTP với service token thật (hiện test gọi thẳng service).
- Giới hạn kích thước request: BPMN đã bị giới hạn bởi `workflow.definition.max-bpmn-bytes`; chưa giới hạn tổng body.
- Bỏ fallback catalog và endpoint sửa luật gán (giai đoạn 2). Màn Hub cho cấu hình môi trường (giai đoạn 3).
