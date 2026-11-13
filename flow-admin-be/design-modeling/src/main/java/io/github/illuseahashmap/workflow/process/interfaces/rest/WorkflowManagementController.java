package io.github.illuseahashmap.workflow.process.interfaces.rest;

import io.github.illuseahashmap.workflow.process.application.ProcessDraftService;
import io.github.illuseahashmap.workflow.process.application.ProcessPublishService;
import io.github.illuseahashmap.workflow.process.application.dto.CreateProcessDraftRequest;
import io.github.illuseahashmap.workflow.process.application.dto.DeployProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.DeployProcessResult;
import io.github.illuseahashmap.workflow.process.application.dto.DraftRevisionRequest;
import io.github.illuseahashmap.workflow.process.application.dto.DraftVersionRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionSummaryView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDraftView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessReleaseView;
import io.github.illuseahashmap.workflow.process.application.dto.PublishProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.PublishProcessResult;
import io.github.illuseahashmap.workflow.process.application.dto.RollbackProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.SaveProcessDraftRequest;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Design of processes: drafts, publishing and the publish history. Same paths as the endpoints Flow Engine had
 * before the split, so the frontend only changes its API client.
 */
@RestController
@RequestMapping("/workflow/management/process")
public class WorkflowManagementController {

    private static final String READ = "hasAnyRole('WF_PLATFORM_ADMIN', 'WF_ADMIN', 'WF_OPERATOR', 'WF_VIEWER')";
    private static final String WRITE = "hasAnyRole('WF_PLATFORM_ADMIN', 'WF_ADMIN')";

    private final ProcessDraftService processDraftService;
    private final ProcessPublishService processPublishService;

    public WorkflowManagementController(ProcessDraftService processDraftService,
                                        ProcessPublishService processPublishService) {
        this.processDraftService = processDraftService;
        this.processPublishService = processPublishService;
    }

    @PostMapping("/{key}/publish")
    @PreAuthorize(WRITE)
    public ApiResponse<PublishProcessResult> publish(@PathVariable String key,
                                                     @Valid @RequestBody PublishProcessRequest request) {
        return ApiResponse.ok(processPublishService.publish(key, request));
    }

    @PostMapping("/{key}/rollback")
    @PreAuthorize(WRITE)
    public ApiResponse<PublishProcessResult> rollback(@PathVariable String key,
                                                      @Valid @RequestBody RollbackProcessRequest request) {
        return ApiResponse.ok(processPublishService.rollback(key, request));
    }

    @PostMapping("/{key}/suspend")
    @PreAuthorize(WRITE)
    public ApiResponse<ProcessReleaseView> suspend(@PathVariable String key) {
        return ApiResponse.ok(processPublishService.suspend(key));
    }

    @PostMapping("/{key}/resume")
    @PreAuthorize(WRITE)
    public ApiResponse<ProcessReleaseView> resume(@PathVariable String key) {
        return ApiResponse.ok(processPublishService.resume(key));
    }

    @GetMapping("/{key}/releases")
    @PreAuthorize(READ)
    public ApiResponse<List<ProcessReleaseView>> releases(@PathVariable String key) {
        return ApiResponse.ok(processPublishService.releases(key));
    }

    @PostMapping("/drafts")
    @PreAuthorize(WRITE)
    public ApiResponse<ProcessDraftView> createDraft(@Valid @RequestBody CreateProcessDraftRequest request) {
        return ApiResponse.ok(processDraftService.create(request));
    }

    @GetMapping("/drafts/{key}")
    @PreAuthorize(READ)
    public ApiResponse<ProcessDraftView> draft(@PathVariable String key) {
        return ApiResponse.ok(processDraftService.get(key));
    }

    @PutMapping("/drafts/{key}")
    @PreAuthorize(WRITE)
    public ApiResponse<ProcessDraftView> saveDraft(@PathVariable String key,
                                                   @Valid @RequestBody SaveProcessDraftRequest request) {
        return ApiResponse.ok(processDraftService.save(key, request));
    }

    @PostMapping("/drafts/{key}/load-version")
    @PreAuthorize(WRITE)
    public ApiResponse<ProcessDraftView> loadVersionIntoDraft(@PathVariable String key,
                                                              @Valid @RequestBody DraftVersionRequest request) {
        return ApiResponse.ok(processDraftService.loadVersion(key, request));
    }

    @PostMapping("/drafts/{key}/discard")
    @PreAuthorize(WRITE)
    public ApiResponse<ProcessDraftView> discardDraft(@PathVariable String key,
                                                      @Valid @RequestBody DraftRevisionRequest request) {
        return ApiResponse.ok(processDraftService.discard(key, request));
    }

    /** CI path: overwrite the draft with this XML and publish it. */
    @PostMapping("/deploy")
    @PreAuthorize(WRITE)
    public ApiResponse<DeployProcessResult> deploy(@Valid @RequestBody DeployProcessRequest request) {
        PublishProcessResult published = processPublishService.publishXml(request.processDefinitionKey().trim(),
                request.processDefinitionName().trim(), request.bpmnXml());
        return ApiResponse.ok(new DeployProcessResult(published.deploymentId(), published.processDefinitionId(),
                published.processDefinitionKey(), published.processDefinitionName(), published.version()));
    }

    @GetMapping("/definitions/page")
    @PreAuthorize(READ)
    public ApiResponse<PageResult<ProcessDefinitionSummaryView>> pageDefinitions(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String processDefinitionKey,
            @RequestParam(required = false) String processDefinitionName,
            @RequestParam(defaultValue = "all") String publishStatus) {
        return ApiResponse.ok(processPublishService.page(
                pageNum, pageSize, processDefinitionKey, processDefinitionName, publishStatus));
    }

    /** The whole process: every version in Flow Engine, the draft and the publish history. */
    @DeleteMapping("/definition")
    @PreAuthorize(WRITE)
    public ApiResponse<Void> deleteDefinitions(@RequestParam String processDefinitionKey) {
        processPublishService.delete(processDefinitionKey);
        return ApiResponse.ok();
    }
}
