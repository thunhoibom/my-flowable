# Script DB (chạy tay)

Không dùng Flyway. Flow Engine và Flow Admin BE không tự sửa schema khi khởi động; mọi thay đổi DB
là file SQL trong thư mục này và được chạy tay.

## Quy ước

- Tên file `V<số>__<mô_tả>.sql`, số tăng liên tục (V1, V2, …). Thay đổi mới lấy số tiếp theo.
- Không sửa file đã chạy ở bất kỳ DB nào; cần sửa thì thêm file mới.
- Script phải chạy được nối tiếp các file trước trên DB trống: test tích hợp (`engine-boot`, Testcontainers)
  chạy toàn bộ thư mục này theo thứ tự số (`DatabaseScripts`).
- Không có bảng nào ghi lại DB đã chạy tới file nào. Tự ghi lại khi chạy.

## Cách chạy

Từ V16 trở đi có bảng bật RLS bắt buộc, nên phiên chạy script phải đặt `app.system_worker=true`
(trừ khi chạy bằng superuser). Mỗi file chạy trong một transaction:

```shell
PGOPTIONS='-c app.system_worker=true' psql "postgresql://postgres@localhost:5432/workflow_agent" \
  -v ON_ERROR_STOP=1 -1 -f db/V48__mo_ta.sql
```

PowerShell: `$env:PGOPTIONS='-c app.system_worker=true'` rồi chạy `psql` như trên.

DB mới: chạy lần lượt V1 → file mới nhất. V31 bật RLS cho các bảng `act_*` của Flowable đang có lúc chạy.

## Đã có

- V1–V43: nền tảng, xem từng file.
- V44–V46: `work_secret`, `work_oauth2_client`, `work_tenant_variable` (Hub: Secret, OAuth2 client, Biến tenant).
- V47: bỏ `work_service_environment` (thay bằng V44–V46 và model dịch vụ mang base URL + xác thực).

Sau V47 chạy `flow-admin-be/db/V1__service_reference_auth.sql` (bảng catalog của Flow Admin BE).
