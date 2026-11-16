package io.github.illuseahashmap.workflow.process.interfaces.rest;

import io.github.illuseahashmap.workflow.process.application.WorkQueryService;
import io.github.illuseahashmap.workflow.process.application.dto.InboxTaskView;
import io.github.illuseahashmap.workflow.process.application.dto.MyProcessView;
import io.github.illuseahashmap.workflow.process.application.dto.StartableProcessView;
import io.github.illuseahashmap.workflow.shared.context.ApiRoles;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Runtime API for the Work area: the current user's tasks, the processes he started, and what he can start. */
@RestController
@RequestMapping("/api/runtime")
public class WorkController {

    private final WorkQueryService work;

    public WorkController(WorkQueryService work) {
        this.work = work;
    }

    @GetMapping("/tasks/inbox")
    @PreAuthorize(ApiRoles.READ)
    public ApiResponse<PageResult<InboxTaskView>> inbox(@RequestParam(defaultValue = "1") Integer pageNum,
                                                        @RequestParam(defaultValue = "20") Integer pageSize,
                                                        @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(work.inbox(pageNum, pageSize, keyword));
    }

    @GetMapping("/process-instances/mine")
    @PreAuthorize(ApiRoles.READ)
    public ApiResponse<PageResult<MyProcessView>> mine(@RequestParam(defaultValue = "1") Integer pageNum,
                                                       @RequestParam(defaultValue = "20") Integer pageSize,
                                                       @RequestParam(defaultValue = "all") String status) {
        return ApiResponse.ok(work.myProcesses(pageNum, pageSize, status));
    }

    @GetMapping("/process-definitions/startable")
    @PreAuthorize(ApiRoles.OPERATE)
    public ApiResponse<List<StartableProcessView>> startable() {
        return ApiResponse.ok(work.startable());
    }
}
