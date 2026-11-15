# Plan giai đoạn 4: chia khu vực frontend

Spec gốc: `F:\flowable\2026-11-13-flow-admin-engine-architecture-design.md`, mục "Lộ trình", giai đoạn 4. Giai đoạn 3: `flow-engine/docs/superpowers/plans/2026-11-15-admin-api.md`.

**Xong khi:** ba khu vực Design, Hub, Work không import lẫn nhau; khu vực Work chỉ gọi Runtime API; các endpoint cũ đã bỏ.

## Bối cảnh (người dùng xác nhận 2026-11-15)

Hệ thống **chưa deploy ở đâu**, chỉ chạy trên máy local. Không có hệ thống ngoài nào gọi vào Flow Engine: client dịch vụ duy nhất là `flow-admin-be`. Vì vậy:

- Đổi tên hoặc xoá API thì sửa luôn bên gọi, trong cùng lần thay đổi. Không giữ endpoint cũ song song.
- Không cần redirect URL cũ, không chia đợt phát hành, không cần thứ tự deploy.
- Quy tắc "API chỉ thêm, không phá" của spec chỉ áp dụng từ khi lên môi trường thật.

## Quyết định

| # | Nội dung |
|---|---|
| 1 | **Runtime API là `/api/runtime/**`**, đúng như spec. Đổi `/workflow/process/**` và `/workflow/task/**` hiện có sang tiền tố này. Ba nhóm API giờ cùng một kiểu: `/api/runtime`, `/api/admin`, `/api/deployment` |
| 2 | **Đóng băng Flow Engine ở cuối giai đoạn 4**, không phải cuối giai đoạn 3, vì Work cần API mới và phải bỏ endpoint cũ |
| 3 | `/auth/**` chỉ còn là API phiên dùng chung: đăng nhập, refresh, me, tenant, sitemap, CSRF. Danh bạ người dùng chuyển vào Runtime API (cho Work) và Admin API (cho Hub, đã có) |
| 4 | Agent (đang ẩn, `agentsEnabled=false`) giữ nguyên `/agents/**`, vì nằm ngoài phạm vi spec. Ghi là ngoại lệ |

## Cấu trúc đích

```
src/
  shared/           không import areas/
    api/            http.ts (createClient, refresh phiên, CSRF), queryKeys.ts
    auth/           (từ features/auth) đăng nhập, quyền, sitemap, menu
    components/     ListEmptyState, MetricCard, PageHeader, StatusBadge, TablePagination,
                    TableTagCell, UserPickerDialog (nhận hàm tìm qua prop),
                    ProcessDiagram + diagramMarkers (bpmn-js viewer, Hub và Work dùng chung)
    layouts/        AppShell (menu chia nhóm theo khu vực)
    router/         guard, meta.d.ts
    stores/ utils/ types/ directives/ config/ styles/ views/ (NoAccess, NotFound)
  areas/
    design/         api.ts (Flow Admin BE + đọc Admin API), routes.ts
      bpmn/         (từ src/bpmn) module modeler
      process-definition/  service-registry/  event-registry/  agent/
    hub/            api.ts (chỉ `/api/admin/**`), routes.ts
      instances/    danh sách + chi tiết vận hành: kết thúc, giao lại, điểm chờ, kích receive task
      failed-jobs/  service-environments/  assignment-rules/  tenants/  members/
    work/           api.ts (chỉ `/api/runtime/**`), routes.ts
      inbox/        hộp việc (mới)
      tasks/        chi tiết task: đồng ý, trả lại, chuyển giao, form tương tác
      my-processes/ quy trình tôi đã start (mới)
      start/        StartProcessDialog, interaction, ParticipantAssignmentEditor
  router/index.ts   gộp routes của ba khu vực
```

## Xếp file hiện có vào khu vực

| Hiện tại | Đích | Ghi chú |
|---|---|---|
| `api/`, `components/`, `layouts/`, `stores/`, `utils/`, `types/`, `directives/`, `config/`, `styles/`, `views/`, `features/auth` | `shared/` | Chuyển nguyên |
| `bpmn/` | `areas/design/bpmn/` | Chỉ designer dùng. `utils/bpmn.ts` ở lại `shared/` nếu Hub/Work còn dùng |
| `features/process-definition`, `service-registry`, `event-registry`, `agent` | `areas/design/` | Bỏ nút Start ở danh sách quy trình, thay bằng link `/work/start?processKey=…` |
| `features/process-instance/views/ProcessInstanceListView.vue` | `areas/hub/instances/` | |
| `features/process-instance/views/ProcessInstanceDetailView.vue` | Tách đôi | Phần vận hành vào `hub/instances/`. Đồng ý, trả lại, chuyển giao, form tương tác vào `work/tasks/` |
| `features/process-instance/components/StartProcessDialog.vue`, `ParticipantAssignmentEditor.vue`, `InteractionDataFields.vue`, `interaction.ts`, `startProcess.ts` | `areas/work/start/` | Danh sách quy trình lấy từ API mới `startable` |
| `ProcessDiagram.vue`, `diagramMarkers.ts` | `shared/components/` | Hub và Work cùng dùng |
| `ProcessWaitsPanel.vue`, `waitVariables.ts` | `areas/hub/instances/` | |
| `UserPickerDialog.vue` | `shared/components/` | Bỏ import `accessApi`, nhận prop `search(keyword, page)`. Hub truyền hàm gọi Admin API, Work truyền hàm gọi Runtime API |
| `features/failed-job`, `service-environment`, `tenant`, `access`, `assignment-rule` | `areas/hub/` | `assignment-rule` lấy danh sách version qua client Hub, không import `process-definition` nữa |

## Các bước

Mỗi bước giữ build xanh: type-check, unit test, lint và prettier trên file đã sửa. Làm một mạch, không commit (như giai đoạn 2, 3) trừ khi được yêu cầu.

### Bước 0. Chuẩn bị test

- Sửa e2e không nạp được: `e2e/workflow.spec.ts` import `src/config/product.ts`, file này dùng `import.meta.env` nên lỗi khi Playwright nạp. Cho e2e đọc cờ từ `process.env`.
- `playwright.config.ts` đang chạy `corepack pnpm dev`. Cho phép dùng `npx vite` khi máy không có pnpm.
- Chạy e2e hiện có để lấy mốc trước khi di chuyển file.

### Bước 1. Flow Engine: Runtime API và dọn endpoint cũ (một lần)

**Đổi tiền tố:** `WorkflowRuntimeController` từ `/workflow` sang `/api/runtime`. Các đường dẫn con giữ nguyên: `process/start`, `process/interaction`, `task/approve`, ... Sửa luôn:
- test,
- `SecurityConfig`: Runtime API gọi bằng Bearer thì bỏ qua CSRF, giống Admin API,
- README (hợp đồng service token),
- `docs/architecture/workflow-agent-interaction-contract.md`.

**API mới cho Work.** Role: start và xử lý là OPERATE; đọc thì thêm VIEWER.

| Endpoint | Nội dung |
|---|---|
| `GET /api/runtime/tasks/inbox` (pageNum, pageSize, keyword) | Task đang chạy trong tenant có `assignee = tôi` hoặc tôi là candidate user, theo đúng luật `TaskViewAssembler.canOperate` (chưa hỗ trợ nhóm). Kèm tên quy trình, business key, thời điểm tạo |
| `GET /api/runtime/process-instances/mine` (pageNum, pageSize, status) | Instance tôi đã start (`startedBy`), cả đang chạy và đã xong |
| `GET /api/runtime/process-definitions/startable` | Version đang chạy của mỗi quy trình trong tenant (key, tên, version, id), không lấy bản bị suspend |
| `GET /api/runtime/directory/users` | Như `/api/admin/directory/users`, cho hộp thoại chuyển việc |

Màn chi tiết task dùng các API có sẵn, sau khi đổi tiền tố: `task/status`, `process/status`, `process/definition/diagram`, `task/interaction`.

**Cho Design:** thêm `GET /api/admin/process-definitions/{key}/start-inputs`, dùng chung service với `process/interaction`. Màn cấu hình call activity sẽ đọc tham số start ở đây, không gọi Runtime API nữa.

**Xoá endpoint cũ:**
- `WorkflowManagementController`, `ReceiveTaskController`, `EventWaitController`, `EventModelController` (`/workflow/management/**`).
- `WorkflowTenantController` (`/workflow/tenant`), `AccessManagementController` (`/auth/access`), `UserDirectoryController` (`/auth/directory`), `AssignmentRuleController` (`/workflow/node-assignment-rule`).

Sửa test và tài liệu đang trỏ tới chúng. Còn lại: `/auth/**` (phiên), `/api/runtime/**`, `/api/admin/**`, `/api/deployment/**`, `/service-callbacks/**`, `/platform-api/**`, `/agents/**`.

**Test:**
- Unit trên engine Flowable trong bộ nhớ cho ba API đọc: tách tenant; người khác không thấy task của mình; candidate user thấy, candidate group không thấy.
- IT phân quyền: cần Docker.

### Bước 2. `shared/`

- Di chuyển file bằng script, rồi sửa import (`@/api/http` thành `@/shared/api/http`, ...).
- Đổi `UserPickerDialog` sang nhận hàm tìm qua prop.
- Đưa `ProcessDiagram` và `diagramMarkers` vào `shared/components`.
- Bước này không đổi hành vi; toàn bộ test hiện có phải qua mà không sửa logic.

### Bước 3. `areas/design`

Chuyển `bpmn/`, `process-definition`, `service-registry`, `event-registry`, `agent`.

`areas/design/api.ts` gồm hai client:
- `adminBe`: Flow Admin BE.
- `deployed`: đọc Admin API (version, callers/callees, start-inputs, usages/waits của event).

### Bước 4. `areas/hub`

- Chuyển `failed-job`, `service-environment`, `tenant`, `access`, `assignment-rule`, cùng danh sách và chi tiết instance.
- Chi tiết instance phía Hub bỏ Đồng ý, Trả lại, Chuyển giao; giữ Kết thúc, Giao lại, điểm chờ, kích receive task. Nếu người xem đang giữ task thì hiện link "Mở ở Công việc".
- `areas/hub/api.ts`: một client duy nhất có tiền tố `/api/admin`.

### Bước 5. `areas/work`

- **Hộp việc** (`/work/inbox`, màn mặc định của OPERATE): bảng task, lọc, nút Start quy trình.
- **Chi tiết task** (`/work/tasks/:taskId`): thông tin task và instance, sơ đồ, form tương tác, Đồng ý / Trả lại / Chuyển giao. Logic lấy từ `ProcessInstanceDetailView` hiện tại.
- **Quy trình của tôi** (`/work/processes`).
- **Start**: dialog mở từ hộp việc, và `/work/start?processKey=` cho link từ Design.
- `areas/work/api.ts`: một client duy nhất có tiền tố `/api/runtime`.

### Bước 6. Router và menu

- Tiền tố route:
  - Design: `/design/processes`, `/design/processes/designer`, `/design/services`, `/design/events`, `/design/agents`.
  - Hub: `/hub/instances[/:id]`, `/hub/failed-jobs`, `/hub/service-environments`, `/hub/assignment-rules`, `/hub/tenants`, `/hub/members`.
  - Work: `/work/inbox`, `/work/tasks/:taskId`, `/work/processes`, `/work/start`.
- Không có redirect từ URL cũ. Sửa luôn mock và đường dẫn trong e2e.
- Route có `meta.area`. AppShell chia menu thành ba nhóm Design, Hub, Work. EIM vẫn quyết mục nào hiện.
- Mã menu EIM mới: `WF_INBOX`, `WF_MY_PROCESS`. Các mã cũ giữ nguyên.
- Màn mặc định sau đăng nhập: OPERATE vào hộp việc, chỉ có quyền thiết kế thì vào Design.

### Bước 7. Luật ranh giới trong `__tests__/architecture.spec.ts`

- File trong `areas/X` không import `areas/Y` (Y khác X).
- `shared/` không import `areas/`.
- Chỉ `areas/X/api.ts` được import client gốc từ `shared/api/http`. File khác trong khu vực dùng client của khu vực mình.
- `areas/work` không chứa `/api/admin` hay client Flow Admin BE.
- `areas/hub` không chứa `/api/runtime` hay client Flow Admin BE.
- `areas/design` không chứa `/api/runtime`.
- Giữ hai luật cũ (public entrypoint của feature), áp cho thư mục con trong mỗi khu vực.

### Bước 8. Tài liệu và đóng băng

- Spec tổng:
  - ghi giai đoạn 4 đã làm;
  - mốc đóng băng dời sang cuối giai đoạn 4;
  - thêm bối cảnh "chưa deploy": quy tắc chỉ thêm, không phá áp dụng từ khi lên môi trường thật.
- README của `flow-admin`: cấu trúc `areas/` và luật ranh giới.
- Ghi bản tổng kết vào file này (mục "Đã làm", "Khác với plan").

Sau bước này **đóng băng Flow Engine**. Flow Admin BE không đổi ở giai đoạn này.

## Rủi ro

- **Di chuyển file nhiều:** khoảng 150 file. Làm bằng script và tách thành bước "chỉ di chuyển, không đổi logic" (bước 2, 3, 4) để dễ soát. Chạy đủ test sau mỗi bước.
- **Bundle budget:** `ProcessDesignerView` đã vượt 420KB từ trước. Di chuyển không được làm nặng thêm; việc giảm cỡ để riêng.
- **Docker không chạy** thì IT của Flow Engine (gồm test phân quyền) bị bỏ qua. Cần bật Docker để chạy trước khi coi bước 1 là xong.
- **Người dùng chỉ có `WF_VIEWER`:** hiện vào được màn "Công việc" (danh sách instance). Sau khi chia, họ thấy Hub (chỉ đọc) và "Quy trình của tôi"; hộp việc không có ý nghĩa với họ.

## Ước lượng

| Bước | Khối lượng |
|---|---|
| 0 | nhỏ |
| 1 | vừa: đổi tiền tố, 4 endpoint mới + 1 cho Design, xoá 8 controller, test |
| 2–4 | lớn nhưng cơ học: di chuyển, sửa import |
| 5 | vừa: 2 màn mới, tách một màn |
| 6–7 | nhỏ đến vừa |
| 8 | nhỏ |
