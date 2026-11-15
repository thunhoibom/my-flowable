package io.github.illuseahashmap.workflow.process.interfaces.rest;

import io.github.illuseahashmap.workflow.process.application.WorkflowRuntimeService;
import io.github.illuseahashmap.workflow.process.application.dto.ReassignTaskRequest;
import io.github.illuseahashmap.workflow.process.application.dto.TaskView;
import io.github.illuseahashmap.workflow.shared.context.AdminRoles;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API: hand one running task to someone else. Assignment rules are fixed with the version, so this is how an
 * admin deals with a single stuck task (architecture spec, "Luật gán người cố định theo version").
 */
@RestController
@RequestMapping("/api/admin/tasks")
public class AdminTaskController {

    private final WorkflowRuntimeService runtime;

    public AdminTaskController(WorkflowRuntimeService runtime) {
        this.runtime = runtime;
    }

    @PostMapping("/{taskId}/reassign")
    @PreAuthorize(AdminRoles.OPERATE)
    public ApiResponse<TaskView> reassign(@PathVariable String taskId,
                                          @Valid @RequestBody ReassignTaskRequest request) {
        return ApiResponse.ok(runtime.reassign(taskId, request));
    }
}
