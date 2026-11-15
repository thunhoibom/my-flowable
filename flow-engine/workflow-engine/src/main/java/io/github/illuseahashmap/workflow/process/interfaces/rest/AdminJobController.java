package io.github.illuseahashmap.workflow.process.interfaces.rest;

import io.github.illuseahashmap.workflow.process.application.FailedJobService;
import io.github.illuseahashmap.workflow.process.application.dto.FailedJobView;
import io.github.illuseahashmap.workflow.shared.context.AdminRoles;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin API: jobs that failed all their retries (service calls, timers, async steps). */
@RestController
@RequestMapping("/api/admin/failed-jobs")
public class AdminJobController {

    private static final int DEFAULT_RETRIES = 3;

    private final FailedJobService failedJobs;

    public AdminJobController(FailedJobService failedJobs) {
        this.failedJobs = failedJobs;
    }

    @GetMapping
    @PreAuthorize(AdminRoles.READ)
    public ApiResponse<PageResult<FailedJobView>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                                       @RequestParam(defaultValue = "20") Integer pageSize,
                                                       @RequestParam(required = false) String processInstanceId) {
        return ApiResponse.ok(failedJobs.page(pageNum, pageSize, processInstanceId));
    }

    @GetMapping("/{jobId}/stacktrace")
    @PreAuthorize(AdminRoles.READ)
    public ApiResponse<String> stacktrace(@PathVariable String jobId) {
        return ApiResponse.ok(failedJobs.stacktrace(jobId));
    }

    @PostMapping("/{jobId}/retry")
    @PreAuthorize(AdminRoles.OPERATE)
    public ApiResponse<Void> retry(@PathVariable String jobId, @RequestBody(required = false) RetryRequest request) {
        failedJobs.retry(jobId, request == null || request.retries() == null ? DEFAULT_RETRIES : request.retries());
        return ApiResponse.ok();
    }

    @DeleteMapping("/{jobId}")
    @PreAuthorize(AdminRoles.MANAGE)
    public ApiResponse<Void> delete(@PathVariable String jobId) {
        failedJobs.delete(jobId);
        return ApiResponse.ok();
    }

    public record RetryRequest(Integer retries) {
    }
}
