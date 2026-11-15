package io.github.illuseahashmap.workflow.process.interfaces.rest;

import io.github.illuseahashmap.workflow.process.application.WorkflowAdministrationService;
import io.github.illuseahashmap.workflow.process.application.WorkflowDefinitionService;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessReferenceView;
import io.github.illuseahashmap.workflow.shared.context.AdminRoles;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API: what is deployed on this Flow Engine. Publishing, suspend and resume go through Flow Admin BE and the
 * Deployment API, so its release history stays complete.
 */
@RestController
@RequestMapping("/api/admin/process-definitions")
public class AdminProcessDefinitionController {

    private final WorkflowDefinitionService definitions;
    private final WorkflowAdministrationService administration;

    public AdminProcessDefinitionController(WorkflowDefinitionService definitions,
                                            WorkflowAdministrationService administration) {
        this.definitions = definitions;
        this.administration = administration;
    }

    /** All versions of one key, or the deployed processes when no key is given. */
    @GetMapping
    @PreAuthorize(AdminRoles.READ)
    public ApiResponse<List<ProcessDefinitionView>> list(@RequestParam(required = false) String processDefinitionKey) {
        return ApiResponse.ok(definitions.list(processDefinitionKey));
    }

    /** One version; the latest when {@code version} is not given. */
    @GetMapping("/{key}")
    @PreAuthorize(AdminRoles.READ)
    public ApiResponse<ProcessDefinitionView> detail(@PathVariable String key,
                                                     @RequestParam(required = false) Integer version) {
        return ApiResponse.ok(definitions.getDefinition(key, version));
    }

    @GetMapping("/{key}/exists")
    @PreAuthorize(AdminRoles.READ)
    public ApiResponse<Boolean> exists(@PathVariable String key) {
        return ApiResponse.ok(definitions.exists(key));
    }

    /** Deployed processes that call this one ("where used"). */
    @GetMapping("/{key}/callers")
    @PreAuthorize(AdminRoles.READ)
    public ApiResponse<List<ProcessReferenceView>> callers(@PathVariable String key) {
        return ApiResponse.ok(administration.callers(key));
    }

    /** Processes that a version of this one calls; the latest when {@code version} is not given. */
    @GetMapping("/{key}/callees")
    @PreAuthorize(AdminRoles.READ)
    public ApiResponse<List<ProcessReferenceView>> callees(@PathVariable String key,
                                                           @RequestParam(required = false) Integer version) {
        return ApiResponse.ok(administration.callees(key, version));
    }

    @DeleteMapping("/{key}/versions/{version}")
    @PreAuthorize(AdminRoles.MANAGE)
    public ApiResponse<Void> deleteVersion(@PathVariable String key, @PathVariable Integer version) {
        administration.deleteProcessDefinitionVersion(key, version);
        return ApiResponse.ok();
    }
}
