package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.designhub.engine.FlowEngineClient;
import io.github.illuseahashmap.workflow.process.application.ProcessDraftService;
import io.github.illuseahashmap.workflow.process.application.dto.CreateProcessDraftRequest;
import io.github.illuseahashmap.workflow.process.application.dto.DraftRevisionRequest;
import io.github.illuseahashmap.workflow.process.application.dto.DraftVersionRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDraftView;
import io.github.illuseahashmap.workflow.process.application.dto.SaveProcessDraftRequest;
import io.github.illuseahashmap.workflow.process.application.port.ProcessDraftRepository;
import io.github.illuseahashmap.workflow.process.application.port.ProcessReleaseRepository;
import io.github.illuseahashmap.workflow.process.domain.BpmnXmlHash;
import io.github.illuseahashmap.workflow.process.domain.ProcessDraft;
import io.github.illuseahashmap.workflow.process.domain.ProcessRelease;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Drafts live in Flow Admin BE ({@code workflow_process_draft}). What is deployed is asked from Flow Engine through
 * the Deployment API; the publish history tells which BPMN hash each deployed version has.
 */
@Service
public class ProcessDraftServiceImpl implements ProcessDraftService {

    private final ProcessDraftRepository drafts;
    private final ProcessReleaseRepository releases;
    private final FlowEngineClient engine;
    private final BpmnDraftInspector inspector;
    private final TenantProvider tenantProvider;
    private final CurrentPrincipalProvider principalProvider;

    public ProcessDraftServiceImpl(ProcessDraftRepository drafts, ProcessReleaseRepository releases,
                                   FlowEngineClient engine, BpmnDraftInspector inspector,
                                   TenantProvider tenantProvider, CurrentPrincipalProvider principalProvider) {
        this.drafts = drafts;
        this.releases = releases;
        this.engine = engine;
        this.inspector = inspector;
        this.tenantProvider = tenantProvider;
        this.principalProvider = principalProvider;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProcessDraftView create(CreateProcessDraftRequest request) {
        String tenantId = tenantId();
        String key = request.processDefinitionKey().trim();
        inspector.check(key, request.bpmnXml());
        if (engine.process(key).isPresent()
                || !drafts.insert(ProcessDraft.of(tenantId, key, request.name().trim(), request.bpmnXml(),
                        1, actor(), now()))) {
            throw new BusinessException(ErrorCode.CONFLICT, "Process key already exists: " + key);
        }
        return get(key);
    }

    @Override
    public ProcessDraftView get(String key) {
        String tenantId = tenantId();
        Optional<FlowEngineClient.Process> latest = engine.process(key);
        Optional<ProcessDraft> draft = drafts.find(tenantId, key);
        if (draft.isEmpty() && latest.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process draft does not exist");
        }
        if (draft.isEmpty()) {
            FlowEngineClient.ProcessVersion content = engine.processVersion(key, null);
            return new ProcessDraftView(key, nameOf(content.name(), key), content.bpmnXml(), 0, null, null,
                    latest.get().version(), latest.get().suspended(), false);
        }
        ProcessDraft value = draft.get();
        String latestHash = latest.map(this::hashOf).orElse(null);
        return new ProcessDraftView(key, value.name(), value.bpmnXml(), value.revision(), value.updatedBy(),
                value.updatedAt(), latest.map(FlowEngineClient.Process::version).orElse(null),
                latest.map(FlowEngineClient.Process::suspended).orElse(false),
                latestHash == null || !latestHash.equals(value.xmlSha256()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProcessDraftView save(String key, SaveProcessDraftRequest request) {
        inspector.check(key, request.bpmnXml());
        write(key, request.name().trim(), request.bpmnXml(), request.revision());
        return get(key);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProcessDraftView loadVersion(String key, DraftVersionRequest request) {
        FlowEngineClient.ProcessVersion version = engine.processVersion(key, request.version());
        write(key, nameOf(version.name(), key), version.bpmnXml(), request.revision());
        return get(key);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProcessDraftView discard(String key, DraftRevisionRequest request) {
        FlowEngineClient.ProcessVersion latest = engine.processVersion(key, null);
        write(key, nameOf(latest.name(), key), latest.bpmnXml(), request.revision());
        return get(key);
    }

    /** The BPMN hash of a deployed version: from the publish history, or from the engine when it has none. */
    private String hashOf(FlowEngineClient.Process process) {
        ProcessRelease release = releases.latestByDefinition(tenantId()).get(process.processDefinitionId());
        if (release != null) {
            return release.xmlSha256();
        }
        return BpmnXmlHash.of(engine.processVersion(process.processKey(), process.version()).bpmnXml());
    }

    /** Revision 0 means "no draft row yet", which is only valid for keys that are deployed. */
    private void write(String key, String name, String xml, long expectedRevision) {
        String tenantId = tenantId();
        Optional<ProcessDraft> current = drafts.find(tenantId, key);
        boolean written;
        if (current.isPresent()) {
            written = drafts.update(current.get().next(name, xml, actor(), now()), expectedRevision);
        } else if (engine.process(key).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process draft does not exist");
        } else {
            written = expectedRevision == 0
                    && drafts.insert(ProcessDraft.of(tenantId, key, name, xml, 1, actor(), now()));
        }
        if (!written) {
            throw new BusinessException(ErrorCode.DRAFT_REVISION_CONFLICT,
                    "Bản nháp đã được người khác sửa. Hãy tải lại.");
        }
    }

    private static String nameOf(String name, String key) {
        return StringUtils.hasText(name) ? name : key;
    }

    private String tenantId() {
        return tenantProvider.current().tenantId();
    }

    private String actor() {
        CurrentPrincipal principal = principalProvider.current();
        return StringUtils.hasText(principal.username()) ? principal.username() : principal.principalId();
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
