package io.github.illuseahashmap.workflow.process.interfaces.rest;

import io.github.illuseahashmap.workflow.process.application.ProcessDeploymentService;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDeploymentRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDeploymentResult;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessVersionContent;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deployment API for processes. Only Flow Admin BE calls it, with a service token limited to
 * {@code /api/deployment/**}; {@code X-Acting-User} names the person for the audit.
 */
@RestController
@RequestMapping("/api/deployment/processes")
@PreAuthorize("hasRole('SERVICE')")
public class ProcessDeploymentController {

    private final ProcessDeploymentService deployments;

    public ProcessDeploymentController(ProcessDeploymentService deployments) {
        this.deployments = deployments;
    }

    @PostMapping
    public ApiResponse<ProcessDeploymentResult> deploy(
            @RequestHeader(value = ActingUser.HEADER, required = false) String actingUser,
            @Valid @RequestBody ProcessDeploymentRequest request) {
        ActingUser.apply(actingUser);
        return ApiResponse.ok(deployments.deploy(request));
    }

    /** The latest version of every deployed key. */
    @GetMapping
    public ApiResponse<List<ProcessDeploymentResult>> list() {
        return ApiResponse.ok(deployments.list());
    }

    /** Whether the key is deployed, and its latest version. */
    @GetMapping("/{key}")
    public ApiResponse<ProcessDeploymentResult> latest(@PathVariable String key) {
        return ApiResponse.ok(deployments.latest(key)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Process " + key + " is not deployed")));
    }

    /** BPMN and services of the latest version. */
    @GetMapping("/{key}/content")
    public ApiResponse<ProcessVersionContent> latestContent(@PathVariable String key) {
        return ApiResponse.ok(deployments.version(key, null));
    }

    /** BPMN and services of one version. */
    @GetMapping("/{key}/versions/{version}")
    public ApiResponse<ProcessVersionContent> version(@PathVariable String key, @PathVariable int version) {
        return ApiResponse.ok(deployments.version(key, version));
    }

    @PostMapping("/{key}/suspend")
    public ApiResponse<ProcessDeploymentResult> suspend(
            @RequestHeader(value = ActingUser.HEADER, required = false) String actingUser, @PathVariable String key) {
        ActingUser.apply(actingUser);
        return ApiResponse.ok(deployments.suspend(key));
    }

    @PostMapping("/{key}/resume")
    public ApiResponse<ProcessDeploymentResult> resume(
            @RequestHeader(value = ActingUser.HEADER, required = false) String actingUser, @PathVariable String key) {
        ActingUser.apply(actingUser);
        return ApiResponse.ok(deployments.resume(key));
    }

    @DeleteMapping("/{key}/versions/{version}")
    public ApiResponse<Void> deleteVersion(
            @RequestHeader(value = ActingUser.HEADER, required = false) String actingUser,
            @PathVariable String key, @PathVariable int version) {
        ActingUser.apply(actingUser);
        deployments.deleteVersion(key, version);
        return ApiResponse.ok();
    }

    /** Every version of the key. Refused while an instance runs or another process calls it. */
    @DeleteMapping("/{key}")
    public ApiResponse<Void> deleteProcess(
            @RequestHeader(value = ActingUser.HEADER, required = false) String actingUser,
            @PathVariable String key) {
        ActingUser.apply(actingUser);
        deployments.deleteProcess(key);
        return ApiResponse.ok();
    }
}
