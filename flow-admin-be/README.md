# flow-admin-be

Flow Admin BE: API quản trị cho giao diện Flow Admin. App này tương đương Flowable Design gộp với Flowable Hub:

- **Design:** soạn bản nháp quy trình, catalog dịch vụ, event, luật gán người; đóng gói và phát hành sang Flow Engine.
- **Hub:** giám sát và vận hành Flow Engine qua Admin API của Flow Engine.

Kiến trúc tổng thể nằm ở `F:\flowable\2026-11-13-flow-admin-engine-architecture-design.md`, ngoài cả 3 repo.

## Các repo liên quan

| Repo | Thành phần | Vai trò |
|---|---|---|
| `flow-engine` | Flow Engine | Core Flowable, chạy quy trình |
| `flow-admin-be` | Flow Admin BE | Backend Design+Hub (repo này) |
| `flow-admin` | Flow Admin | Frontend chung cho Design, Hub và Work |

## Trạng thái

Giai đoạn 2 của spec: phần Design đã chuyển từ `flow-engine` sang đây. Hub làm ở giai đoạn 3.

Module:

- `shared-kernel`: bản chép từ `flow-engine`, phát triển độc lập.
- `design-modeling`: Design. Bản nháp quy trình và phát hành, catalog dịch vụ, thư viện event/channel, client gọi Flow Engine (`designhub.engine.FlowEngineClient`). Java package giữ như trong `flow-engine`.
- `hub-admin`: khung rỗng cho Hub (giai đoạn 3).
- `admin-be-migrations`: Flyway cho bảng của Flow Admin BE, chung schema `public` với Flow Engine, bảng lịch sử riêng `flyway_schema_history_admin_be`. Chưa có migration nào: các bảng dùng lại (`workflow_process_draft`, `workflow_process_release`, `service_definition`, `service_operation_draft`, `service_publication`, `workflow_event_registry_draft`, `workflow_event_registry_release`) do migration cũ của Flow Engine tạo.
- `admin-be-boot`: app Spring Boot, cổng `8081`.

## Flow Admin BE nói chuyện với Flow Engine thế nào

- **Xác thực người dùng:** Flow Admin BE không tự kiểm Keycloak. Mỗi request mang token của người dùng (Bearer hoặc cookie phiên do Flow Engine đặt) và `X-Tenant-Code`; Flow Admin BE hỏi `GET /api/admin/me` của Flow Engine và cache kết quả 60 giây. Role là `eim_roles`, như Flow Engine.
- **Phát hành:** gọi Deployment API (`/api/deployment/**`) bằng service token của client `flow-admin-be` (seed ở V43 của Flow Engine), kèm `X-Acting-User`. Secret dùng chung: `FLOW_ADMIN_BE_SERVICE_SECRET`, đặt giống nhau ở cả hai app.
- **Cấu hình môi trường của dịch vụ:** khi publish một dịch vụ trong catalog, base URL (theo `WORKFLOW_SERVICES_ENVIRONMENT`) và credential được ghi sang `work_service_environment` của Flow Engine qua `PUT /api/admin/service-environments/{key}`, bằng token của người dùng. Tạm thời, tới khi Hub có màn riêng (giai đoạn 3).
- **Luật gán người:** vẫn sửa trên version đã deploy ở Flow Engine (`/workflow/node-assignment-rule`). Khi phát hành, Flow Admin BE yêu cầu Flow Engine chép luật từ version trước (`copyAssignmentsFromLatest`); khi rollback thì chép từ version được khôi phục.
- Tắt Flow Admin BE thì Flow Engine vẫn chạy đủ: start, duyệt, callback, timer, event.

## Chạy

Yêu cầu Java 25 và Maven 3.9. Chạy Flow Engine trước (nó tạo các bảng và xác thực người dùng). Biến môi trường: xem `.env.example`.

```bash
mvn -pl admin-be-boot -am spring-boot:run
# kiểm tra
curl http://localhost:8081/actuator/health
```

Không cài Maven thì build bằng Docker:

```bash
docker build -t flow-admin-be .
docker run --rm -p 8081:8081 flow-admin-be
```
