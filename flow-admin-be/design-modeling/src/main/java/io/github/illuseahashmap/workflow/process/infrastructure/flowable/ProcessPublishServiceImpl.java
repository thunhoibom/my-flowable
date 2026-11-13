package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.designhub.engine.FlowEngineClient;
import io.github.illuseahashmap.serviceregistry.domain.ServiceCatalog;
import io.github.illuseahashmap.serviceregistry.engine.ServiceModelJson;
import io.github.illuseahashmap.workflow.process.application.ProcessPublishService;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionSummaryView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessReleaseView;
import io.github.illuseahashmap.workflow.process.application.dto.PublishProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.PublishProcessResult;
import io.github.illuseahashmap.workflow.process.application.dto.RollbackProcessRequest;
import io.github.illuseahashmap.workflow.process.application.port.ProcessDraftRepository;
import io.github.illuseahashmap.workflow.process.application.port.ProcessReleaseRepository;
import io.github.illuseahashmap.workflow.process.domain.BpmnXmlHash;
import io.github.illuseahashmap.workflow.process.domain.ProcessDraft;
import io.github.illuseahashmap.workflow.process.domain.ProcessDraftHeader;
import io.github.illuseahashmap.workflow.process.domain.ProcessRelease;
import io.github.illuseahashmap.workflow.process.domain.ReleaseAction;
import io.github.illuseahashmap.workflow.process.domain.ServiceTaskBinding;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Publishing from Design (architecture spec, "Luồng phát hành"): build one deployment request (BPMN, the design of
 * every called service, which rules to copy), send it to Flow Engine, and record the result in the publish history.
 * Flow Engine runs every guard again and may refuse; its message comes back unchanged.
 */
@Service
public class ProcessPublishServiceImpl implements ProcessPublishService {

    private static final String PUBLISHED = "published";
    private static final String SUSPENDED = "suspended";
    private static final String DRAFT_ONLY = "draft_only";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final ProcessDraftRepository drafts;
    private final ProcessReleaseRepository releases;
    private final FlowEngineClient engine;
    private final ServiceCatalog catalog;
    private final BpmnDraftInspector inspector;
    private final XmlServiceTaskBindingParser serviceTasks;
    private final WorkflowOperationAuditService auditService;
    private final TenantProvider tenantProvider;
    private final CurrentPrincipalProvider principalProvider;
    private final ObjectMapper json = new ObjectMapper();

    public ProcessPublishServiceImpl(ProcessDraftRepository drafts, ProcessReleaseRepository releases,
                                     FlowEngineClient engine, ServiceCatalog catalog, BpmnDraftInspector inspector,
                                     XmlServiceTaskBindingParser serviceTasks,
                                     WorkflowOperationAuditService auditService, TenantProvider tenantProvider,
                                     CurrentPrincipalProvider principalProvider) {
        this.drafts = drafts;
        this.releases = releases;
        this.engine = engine;
        this.catalog = catalog;
        this.inspector = inspector;
        this.serviceTasks = serviceTasks;
        this.auditService = auditService;
        this.tenantProvider = tenantProvider;
        this.principalProvider = principalProvider;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PublishProcessResult publish(String key, PublishProcessRequest request) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        ProcessDraft draft = drafts.findForUpdate(tenant.tenantId(), key)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Process draft does not exist"));
        if (draft.revision() != request.revision()) {
            throw new BusinessException(ErrorCode.DRAFT_REVISION_CONFLICT,
                    "Bản nháp đã được người khác sửa. Hãy tải lại.");
        }
        Map<String, Map<String, Object>> services = services(draft.bpmnXml(), Map.of());
        Optional<FlowEngineClient.Process> previous = engine.process(key);
        if (previous.isPresent() && !previous.get().suspended() && unchanged(previous.get(), draft, services)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Bản nháp giống bản đang chạy, không có gì để phát hành");
        }
        FlowEngineClient.Process deployed = deploy(key, draft.name(), draft.bpmnXml(), services, true, null);
        record(tenant, deployed, ReleaseAction.PUBLISH, null, draft.xmlSha256());
        auditService.record("PROCESS_DEFINITION_PUBLISHED", null, key, null,
                "version=" + deployed.version(), null, "PUBLISHED", null);
        return result(deployed);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PublishProcessResult publishXml(String key, String name, String bpmnXml) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        inspector.check(key, bpmnXml);
        ProcessDraft current = drafts.findForUpdate(tenant.tenantId(), key).orElse(null);
        ProcessDraft next = current == null
                ? ProcessDraft.of(tenant.tenantId(), key, name, bpmnXml, 1, actor(), now())
                : current.next(name, bpmnXml, actor(), now());
        boolean written = current == null ? drafts.insert(next) : drafts.update(next, current.revision());
        if (!written) {
            throw new BusinessException(ErrorCode.DRAFT_REVISION_CONFLICT, "Process draft changed concurrently");
        }
        // CI may redeploy the same XML. Return the running version instead of failing.
        Optional<FlowEngineClient.Process> latest = engine.process(key);
        if (latest.isPresent() && !latest.get().suspended()
                && unchanged(latest.get(), next, services(bpmnXml, Map.of()))) {
            FlowEngineClient.Process running = latest.get();
            return new PublishProcessResult(running.deploymentId(), running.processDefinitionId(), key,
                    running.name(), running.version(), null, List.of());
        }
        return publish(key, new PublishProcessRequest(next.revision()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PublishProcessResult rollback(String key, RollbackProcessRequest request) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        FlowEngineClient.Process latest = engine.process(key)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Process has no published version"));
        if (latest.version() == request.version()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "This is already the latest version");
        }
        FlowEngineClient.ProcessVersion source = engine.processVersion(key, request.version());
        // Like redeploying an old Flowable app: the services of vK come back with it, not the latest ones.
        Map<String, Map<String, Object>> pinned = new TreeMap<>();
        source.services().forEach((serviceKey, content) -> pinned.put(serviceKey, read(content)));
        FlowEngineClient.Process deployed = deploy(key, source.name(), source.bpmnXml(),
                services(source.bpmnXml(), pinned), false, source.version());
        record(tenant, deployed, ReleaseAction.ROLLBACK, source.version(), BpmnXmlHash.of(source.bpmnXml()));
        auditService.record("PROCESS_DEFINITION_ROLLED_BACK", null, key, null,
                "version=" + deployed.version() + ", source=" + source.version(), null, "PUBLISHED", null);
        return result(deployed);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProcessReleaseView suspend(String key) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        FlowEngineClient.Process latest = engine.suspend(key);
        return view(record(tenant, latest, ReleaseAction.SUSPEND, null, hashOf(latest)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProcessReleaseView resume(String key) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        FlowEngineClient.Process latest = engine.resume(key);
        return view(record(tenant, latest, ReleaseAction.RESUME, null, hashOf(latest)));
    }

    @Override
    public List<ProcessReleaseView> releases(String key) {
        return releases.list(tenantProvider.current().tenantId(), key).stream().map(this::view).toList();
    }

    @Override
    public PageResult<ProcessDefinitionSummaryView> page(Integer pageNum, Integer pageSize, String key, String name,
                                                         String publishStatus) {
        int page = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        String status = normalizeStatus(publishStatus);
        String tenantId = tenantProvider.current().tenantId();
        Map<String, ProcessDraftHeader> draftsByKey = drafts.listHeaders(tenantId).stream()
                .collect(Collectors.toMap(ProcessDraftHeader::processDefinitionKey, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
        Map<String, ProcessRelease> releaseByDefinition = releases.latestByDefinition(tenantId);
        List<ProcessDefinitionSummaryView> summaries = new ArrayList<>();
        for (FlowEngineClient.Process process : engine.processes()) {
            ProcessDraftHeader draft = draftsByKey.remove(process.processKey());
            ProcessRelease release = releaseByDefinition.get(process.processDefinitionId());
            boolean running = !process.suspended();
            boolean changed = draft != null && release != null && !draft.xmlSha256().equals(release.xmlSha256());
            summaries.add(new ProcessDefinitionSummaryView(process.processDefinitionId(), process.processKey(),
                    process.name(), process.version(), process.deploymentId(), process.deployTime(),
                    running ? process.version() : null, running ? process.processDefinitionId() : null,
                    running ? process.deploymentId() : null, release == null ? null : release.performedAt(),
                    running ? PUBLISHED : SUSPENDED, tenantId, changed));
        }
        draftsByKey.values().forEach(draft -> summaries.add(new ProcessDefinitionSummaryView(null,
                draft.processDefinitionKey(), draft.name(), 0, null, draft.updatedAt(), null, null, null, null,
                DRAFT_ONLY, tenantId, true)));
        List<ProcessDefinitionSummaryView> filtered = summaries.stream()
                .filter(summary -> matches(summary.processDefinitionKey(), key))
                .filter(summary -> matches(summary.processDefinitionName(), name))
                .filter(summary -> statusMatches(status, summary.publishStatus()))
                .sorted(Comparator.comparing(ProcessDefinitionSummaryView::latestDeployTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        int from = Math.min((page - 1) * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        return new PageResult<>(filtered.size(), page, size, filtered.subList(from, to));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String key) {
        String tenantId = tenantProvider.current().tenantId();
        boolean deployed = engine.process(key).isPresent();
        if (!deployed && drafts.find(tenantId, key).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process definition does not exist");
        }
        if (deployed) {
            engine.deleteProcess(key);
        }
        drafts.delete(tenantId, key);
        releases.deleteByKey(tenantId, key);
        auditService.record(deployed ? "PROCESS_DEFINITIONS_DELETED" : "PROCESS_DRAFT_DELETED", null, key, null,
                key, deployed ? "DEPLOYED" : "DRAFT", "DELETED", null);
    }

    /**
     * The design of every service the BPMN calls: {@code pinned} first (a rollback keeps the services of the version
     * it restores), then the latest published catalog entry. Base URLs and secrets are left out.
     */
    private Map<String, Map<String, Object>> services(String xml, Map<String, Map<String, Object>> pinned) {
        Set<String> keys;
        try {
            keys = serviceTasks.parse(xml).stream()
                    .map(ServiceTaskBinding::serviceKey)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, exception.getMessage());
        }
        Map<String, Map<String, Object>> services = new TreeMap<>();
        Set<String> missing = new TreeSet<>();
        for (String serviceKey : keys) {
            Map<String, Object> design = pinned.get(serviceKey);
            if (design == null) {
                design = catalog.publishedService(serviceKey)
                        .map(service -> json.convertValue(ServiceModelJson.designOnly(service), MAP))
                        .orElse(null);
            }
            if (design == null) {
                missing.add(serviceKey);
            } else {
                services.put(serviceKey, design);
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Các dịch vụ chưa được publish: " + String.join(", ", missing));
        }
        return services;
    }

    /** True when the version running now has this BPMN and exactly these services. */
    private boolean unchanged(FlowEngineClient.Process running, ProcessDraft draft,
                              Map<String, Map<String, Object>> services) {
        if (!hashOf(running).equals(draft.xmlSha256())) {
            return false;
        }
        Map<String, String> deployedServices = engine.processVersion(running.processKey(), running.version()).services();
        if (!deployedServices.keySet().equals(services.keySet())) {
            return false;
        }
        return services.entrySet().stream().allMatch(entry ->
                tree(deployedServices.get(entry.getKey())).equals(json.valueToTree(entry.getValue())));
    }

    private FlowEngineClient.Process deploy(String key, String name, String xml,
                                            Map<String, Map<String, Object>> services, boolean copyFromLatest,
                                            Integer copyFromVersion) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("publishId", UUID.randomUUID().toString());
        request.put("processKey", key);
        request.put("name", name);
        request.put("bpmnXml", xml);
        request.put("services", List.copyOf(services.values()));
        request.put("assignments", List.of());
        request.put("copyAssignmentsFromLatest", copyFromLatest);
        request.put("copyAssignmentsFromVersion", copyFromVersion);
        return engine.deployProcess(request);
    }

    private String hashOf(FlowEngineClient.Process process) {
        ProcessRelease release = releases.latestByDefinition(tenantProvider.current().tenantId())
                .get(process.processDefinitionId());
        return release != null ? release.xmlSha256()
                : BpmnXmlHash.of(engine.processVersion(process.processKey(), process.version()).bpmnXml());
    }

    private ProcessRelease record(TenantContext.TenantInfo tenant, FlowEngineClient.Process process,
                                  ReleaseAction action, Integer sourceVersion, String hash) {
        ProcessRelease release = new ProcessRelease(null, tenant.tenantId(), process.processKey(),
                process.processDefinitionId(), process.version(), action, sourceVersion, hash, actor(), now());
        releases.append(release);
        return release;
    }

    private static PublishProcessResult result(FlowEngineClient.Process deployed) {
        return new PublishProcessResult(deployed.deploymentId(), deployed.processDefinitionId(),
                deployed.processKey(), deployed.name(), deployed.version(), deployed.assignments(),
                deployed.newUserTaskIds() == null ? List.of() : deployed.newUserTaskIds());
    }

    private ProcessReleaseView view(ProcessRelease release) {
        return new ProcessReleaseView(release.version(), release.action().name(), release.sourceVersion(),
                release.performedBy(), release.performedAt());
    }

    private Map<String, Object> read(String content) {
        try {
            return json.readValue(content, MAP);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.WORKFLOW_ERROR, "Flow Engine trả về định nghĩa dịch vụ hỏng");
        }
    }

    private JsonNode tree(String content) {
        try {
            return content == null ? null : json.readTree(content);
        } catch (IOException exception) {
            return null;
        }
    }

    private static boolean matches(String value, String filter) {
        if (!StringUtils.hasText(filter)) {
            return true;
        }
        return value != null && value.toLowerCase(Locale.ROOT).contains(filter.trim().toLowerCase(Locale.ROOT));
    }

    /** {@code unpublished} is kept for older clients and means "not running". */
    private static boolean statusMatches(String filter, String status) {
        return switch (filter) {
            case "all" -> true;
            case "unpublished" -> !PUBLISHED.equals(status);
            default -> filter.equals(status);
        };
    }

    private static String normalizeStatus(String status) {
        String normalized = StringUtils.hasText(status) ? status.trim().toLowerCase(Locale.ROOT) : "all";
        if (!Set.of("all", PUBLISHED, SUSPENDED, DRAFT_ONLY, "unpublished").contains(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported status: " + status);
        }
        return normalized;
    }

    private String actor() {
        CurrentPrincipal principal = principalProvider.current();
        return StringUtils.hasText(principal.username()) ? principal.username() : principal.principalId();
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
