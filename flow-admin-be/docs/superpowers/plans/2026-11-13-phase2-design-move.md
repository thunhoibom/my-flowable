# Giai đoạn 2: chuyển code soạn thảo sang Flow Admin BE

Spec gốc: `F:\flowable\2026-11-13-flow-admin-engine-architecture-design.md`, mục "Lộ trình", giai đoạn 2. Giai đoạn 1 (phía nhận deploy): `flow-engine/docs/superpowers/plans/2026-11-13-deployment-api.md`.

**Xong khi:** hai backend chạy riêng; tắt Flow Admin BE thì Flow Engine vẫn đi đủ luồng.

## Người dùng chốt (2026-11-13)

- Xác thực ở Flow Admin BE: **hỏi Flow Engine** (`GET /api/admin/me`), không tự cấu hình Keycloak.
- **Không làm luật gán người nháp.** Luật vẫn sửa trên version đã deploy ở Flow Engine; phát hành yêu cầu Flow Engine chép luật từ version trước.
- Làm một mạch, không commit.

## Đã chuyển sang Flow Admin BE (`design-modeling`, giữ nguyên Java package)

| Phần | Lớp chính | Bảng (schema `public`, tên cũ) |
|---|---|---|
| Bản nháp và phát hành quy trình | `ProcessDraftServiceImpl`, `ProcessPublishServiceImpl`, `WorkflowManagementController` | `workflow_process_draft`, `workflow_process_release` |
| Catalog dịch vụ | `JdbcServiceCatalog`, `ServiceCatalogController`, `ServiceEnvironmentSync` | `service_definition`, `service_operation_draft`, `service_publication` |
| Thư viện event/channel | `EventModelServiceImpl`, `RestChannelServiceImpl`, `EngineEventRegistryGateway` | `workflow_event_registry_draft`, `workflow_event_registry_release` |

Đường dẫn REST giữ nguyên như trước (`/workflow/management/process/**`, `/services/**`, `/workflow/management/event-registry/{events,channels}/**`); frontend chỉ đổi API client sang `/api/admin-be`.

## Flow Admin BE gọi Flow Engine (`FlowEngineClient`)

- **Deployment API** bằng service token của client `flow-admin-be` (Flow Engine seed ở V43, secret `FLOW_ADMIN_BE_SERVICE_SECRET` đặt giống nhau ở hai app). Định dạng token giống hệt `ServiceTokenCryptoService` của Flow Engine: `clientCode.base64url(iv || AES-GCM(sha256(secret), payload))`, payload gắn method, path, sha256 body, tenant, nonce. Kèm `X-Acting-User`.
- **Admin API** bằng token của người dùng: `/api/admin/me` (xác thực, cache 60 giây theo token và tenant), `PUT /api/admin/service-environments/{key}` (khi publish dịch vụ).
- Lỗi của Flow Engine trả về nguyên mã và thông báo.

## Phát hành quy trình

1. Đọc bản nháp, kiểm `revision`.
2. Lấy phần thiết kế của mọi dịch vụ BPMN gọi từ bản publish mới nhất của catalog (bỏ URL và secret). Thiếu thì báo lỗi, không gọi Flow Engine.
3. Nếu BPMN và dịch vụ giống hệt version đang chạy thì báo "không có gì để phát hành".
4. `POST /api/deployment/processes` với `publishId` mới và `copyAssignmentsFromLatest: true`.
5. Ghi `workflow_process_release` (PUBLISH, version, hash).

Rollback về vK: đọc BPMN và `.service` của vK từ Flow Engine (`GET /api/deployment/processes/{key}/versions/{k}`), deploy lại với `copyAssignmentsFromVersion: k`. Suspend, resume, xoá quy trình: gọi API tương ứng rồi ghi lịch sử.

Danh sách quy trình của Design = bản nháp ở Flow Admin BE + version mới nhất từ `GET /api/deployment/processes`.

## Đã gỡ khỏi Flow Engine

- Bản nháp, phát hành, catalog, event/channel nháp và các endpoint của chúng; `/workflow/management/process/deploy`, `definition/activate`, `definition/active`.
- Fallback cấu hình môi trường về catalog: runtime chỉ đọc `work_service_environment`.
- Flow Engine không còn đọc hay ghi các bảng soạn thảo ở trên.

Flow Engine giữ: runtime, Deployment API, Admin API (`/api/admin/me`, `/api/admin/service-environments`), sửa luật gán người, `callers`/`callees`, `usages` và `waits` của event, xoá một version.

## Khác với spec

- Lịch sử phát hành không lưu nguyên JSON đã gửi và không có trạng thái PENDING/FAILED: phát hành gọi đồng bộ, chỉ ghi khi Flow Engine trả thành công. Rollback và "mở vK vào bản nháp" đọc nội dung version từ Flow Engine. Đơn giản hơn, và không cần migration cho Flow Admin BE.
- Flow Admin BE không tự kiểm JWT (người dùng chốt), nên Flow Engine tắt thì Design cũng không đăng nhập được. Flow Engine thì vẫn chạy độc lập.
- Base URL và secret của dịch vụ vẫn nhập ở màn catalog; Flow Admin BE chuyển chúng sang Flow Engine khi publish. Màn Hub riêng để ở giai đoạn 3.
- Chưa có Flow Admin BE kiểm guard trước (Flow Engine vẫn là bên quyết định và trả lỗi rõ).

## Test

Flow Admin BE: `FlowEngineClientTest` (token mở được bằng thuật toán của Flow Engine, lỗi, Admin API), `ProcessPublishServiceImplTest` (Flow Engine giả), `EventModelServiceImplTest`, `EngineAuthenticationFilterTest`, các test JDBC chép từ Flow Engine (catalog, bản nháp).
Flow Engine: các test cũ sửa theo, `JdbcCallbackWaitStoreTest`, `JdbcCallReferenceRepositoryTest`, `JdkHttpTransportTest`, IT xác thực gọi `/api/admin/service-environments`.

## Chưa làm

- Test tích hợp chạy thật hai app cùng lúc (hiện kiểm từng phía; định dạng token được kiểm bằng thuật toán giải mã của Flow Engine).
- Luật gán người nháp; bỏ endpoint sửa luật ở Flow Engine.
- Phần Hub (giai đoạn 3) và chia khu vực frontend (giai đoạn 4).
