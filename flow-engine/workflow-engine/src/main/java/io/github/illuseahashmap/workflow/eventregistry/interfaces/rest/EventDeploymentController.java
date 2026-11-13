package io.github.illuseahashmap.workflow.eventregistry.interfaces.rest;

import io.github.illuseahashmap.workflow.eventregistry.application.EventDeploymentService;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.DeployedModelView;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventDeploymentRequest;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventDeploymentResult;
import io.github.illuseahashmap.workflow.process.interfaces.rest.ActingUser;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Deployment API for shared events and channels. Only Flow Admin BE calls it, with a service token. */
@RestController
@PreAuthorize("hasRole('SERVICE')")
public class EventDeploymentController {

    private final EventDeploymentService deployments;

    public EventDeploymentController(EventDeploymentService deployments) {
        this.deployments = deployments;
    }

    @PostMapping("/api/deployment/events")
    public ApiResponse<EventDeploymentResult> deploy(
            @RequestHeader(value = ActingUser.HEADER, required = false) String actingUser,
            @Valid @RequestBody EventDeploymentRequest request) {
        ActingUser.apply(actingUser);
        return ApiResponse.ok(deployments.deploy(request));
    }

    @GetMapping("/api/deployment/events/{key}")
    public ApiResponse<DeployedModelView> event(@PathVariable String key) {
        return ApiResponse.ok(deployments.event(key)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Event " + key + " is not deployed")));
    }

    @GetMapping("/api/deployment/channels/{key}")
    public ApiResponse<DeployedModelView> channel(@PathVariable String key) {
        return ApiResponse.ok(deployments.channel(key)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Channel " + key + " is not deployed")));
    }

    @DeleteMapping("/api/deployment/events/{key}")
    public ApiResponse<Void> deleteEvent(
            @RequestHeader(value = ActingUser.HEADER, required = false) String actingUser,
            @PathVariable String key) {
        ActingUser.apply(actingUser);
        deployments.deleteEvent(key);
        return ApiResponse.ok();
    }

    @DeleteMapping("/api/deployment/channels/{key}")
    public ApiResponse<Void> deleteChannel(
            @RequestHeader(value = ActingUser.HEADER, required = false) String actingUser,
            @PathVariable String key) {
        ActingUser.apply(actingUser);
        deployments.deleteChannel(key);
        return ApiResponse.ok();
    }
}
