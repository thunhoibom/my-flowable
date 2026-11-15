package io.github.illuseahashmap.workflow.process.interfaces.rest;

import io.github.illuseahashmap.workflow.process.application.ReceiveTaskOperationService;
import io.github.illuseahashmap.workflow.process.application.WorkflowAdministrationService;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDiagramDataView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessInstanceDetailView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessInstanceSummaryView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessWaitView;
import io.github.illuseahashmap.workflow.process.application.dto.TerminateProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.TriggerReceiveTaskRequest;
import io.github.illuseahashmap.workflow.shared.context.AdminRoles;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin API: process instances of the current tenant, for Hub. */
@RestController
@RequestMapping("/api/admin/process-instances")
public class AdminProcessInstanceController {

    private final WorkflowAdministrationService administration;
    private final ReceiveTaskOperationService receiveTasks;

    public AdminProcessInstanceController(WorkflowAdministrationService administration,
                                          ReceiveTaskOperationService receiveTasks) {
        this.administration = administration;
        this.receiveTasks = receiveTasks;
    }

    @GetMapping
    @PreAuthorize(AdminRoles.READ)
    public ApiResponse<PageResult<ProcessInstanceSummaryView>> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String processDefinitionKey,
            @RequestParam(required = false) String processDefinitionName,
            @RequestParam(required = false) String processInstanceId,
            @RequestParam(required = false) String businessKey,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "false") boolean rootOnly) {
        return ApiResponse.ok(administration.pageProcessInstances(pageNum, pageSize, processDefinitionKey,
                processDefinitionName, processInstanceId, businessKey, status, rootOnly));
    }

    @GetMapping("/{processInstanceId}")
    @PreAuthorize(AdminRoles.READ)
    public ApiResponse<ProcessInstanceDetailView> detail(@PathVariable String processInstanceId) {
        return ApiResponse.ok(administration.getProcessInstanceDetail(processInstanceId));
    }

    @GetMapping("/{processInstanceId}/diagram-data")
    @PreAuthorize(AdminRoles.READ)
    public ApiResponse<ProcessDiagramDataView> diagramData(@PathVariable String processInstanceId) {
        return ApiResponse.ok(administration.getProcessDiagramData(processInstanceId));
    }

    @GetMapping("/{processInstanceId}/waits")
    @PreAuthorize(AdminRoles.READ)
    public ApiResponse<List<ProcessWaitView>> waits(@PathVariable String processInstanceId) {
        return ApiResponse.ok(receiveTasks.waits(processInstanceId));
    }

    @PostMapping("/{processInstanceId}/terminate")
    @PreAuthorize(AdminRoles.OPERATE)
    public ApiResponse<Void> terminate(@PathVariable String processInstanceId,
                                       @Valid @RequestBody ReasonRequest request) {
        administration.terminateProcessInstance(new TerminateProcessRequest(processInstanceId, request.reason()));
        return ApiResponse.ok();
    }

    /** Continues a manual wait step (receive task). */
    @PostMapping("/{processInstanceId}/receive-tasks/{activityId}/trigger")
    @PreAuthorize(AdminRoles.OPERATE)
    public ApiResponse<Void> trigger(@PathVariable String processInstanceId, @PathVariable String activityId,
                                     @Valid @RequestBody TriggerRequest request) {
        receiveTasks.trigger(new TriggerReceiveTaskRequest(processInstanceId, activityId, request.executionId(),
                request.variables(), request.reason()));
        return ApiResponse.ok();
    }

    public record ReasonRequest(@NotBlank String reason) {
    }

    /** @param executionId required when several branches of a multi-instance step are waiting */
    public record TriggerRequest(String executionId, Map<String, Object> variables, @NotBlank String reason) {
    }
}
