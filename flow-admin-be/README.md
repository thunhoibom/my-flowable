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

Đây là khung ban đầu. Code soạn thảo (bản nháp, catalog, event registry) vẫn đang nằm trong `flow-engine` và sẽ chuyển sang đây theo lộ trình của spec.

Module hiện có:

- `shared-kernel`: bản chép từ `flow-engine`. Repo này tự phát triển tiếp, không đồng bộ ngược về Flow Engine.
- `admin-be-boot`: app Spring Boot, cổng mặc định `8081` (biến `ADMIN_BE_PORT`).

Theo spec, các module sau sẽ được thêm dần: `design-modeling`, `hub-admin`, `admin-be-migrations` (schema `flow_admin`).

## Chạy

Yêu cầu Java 25 và Maven 3.9.

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
