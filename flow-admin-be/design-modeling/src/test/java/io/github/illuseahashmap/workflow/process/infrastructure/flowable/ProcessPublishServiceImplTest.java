package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.designhub.engine.FlowEngineClient;
import io.github.illuseahashmap.designhub.engine.FlowEngineProperties;
import io.github.illuseahashmap.serviceregistry.domain.AuthConfig;
import io.github.illuseahashmap.serviceregistry.domain.AuthType;
import io.github.illuseahashmap.serviceregistry.domain.EnvironmentBaseUrl;
import io.github.illuseahashmap.serviceregistry.domain.HttpMethod;
import io.github.illuseahashmap.serviceregistry.domain.InMemoryServiceCatalog;
import io.github.illuseahashmap.serviceregistry.domain.PublishedOperation;
import io.github.illuseahashmap.workflow.config.ProcessDefinitionProperties;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.process.application.dto.CreateProcessDraftRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionSummaryView;
import io.github.illuseahashmap.workflow.process.application.dto.PublishProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.RollbackProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.SaveProcessDraftRequest;
import io.github.illuseahashmap.workflow.process.domain.ReleaseAction;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Publishing from Design against a fake Flow Engine that keeps every deployment request it received. */
class ProcessPublishServiceImplTest {

    private static final String TENANT = "tenant-design";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final FakeEngine engine = new FakeEngine();
    private final InMemoryServiceCatalog catalog = new InMemoryServiceCatalog();
    private InMemoryProcessRepositories.Drafts drafts;
    private InMemoryProcessRepositories.Releases releases;
    private ProcessDraftServiceImpl draftService;
    private ProcessPublishServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantProvider tenant = () -> new TenantContext.TenantInfo(TENANT, "design", "Design");
        CurrentPrincipalProvider principal =
                () -> new CurrentPrincipal("USER", "u1", "alice", "Alice", "design", Set.of(), Set.of());
        drafts = new InMemoryProcessRepositories.Drafts();
        releases = new InMemoryProcessRepositories.Releases();
        var inspector = new BpmnDraftInspector(new ProcessDefinitionProperties());
        draftService = new ProcessDraftServiceImpl(drafts, releases, engine, inspector, tenant, principal);
        service = new ProcessPublishServiceImpl(drafts, releases, engine, catalog, inspector,
                new XmlServiceTaskBindingParser(), new WorkflowOperationAuditService(principal, tenant), tenant,
                principal);
    }

    @Test
    void publishSendsTheBpmnAndTheDesignOfItsServicesWithoutSecrets() throws Exception {
        publishService("crm", "/leads");
        draftService.create(new CreateProcessDraftRequest("order", "Đơn hàng", xml("order", "crm")));

        var result = service.publish("order", new PublishProcessRequest(1));

        assertThat(result.version()).isEqualTo(1);
        Map<String, Object> request = engine.requests.getFirst();
        assertThat(request.get("processKey")).isEqualTo("order");
        assertThat(request.get("copyAssignmentsFromLatest")).isEqualTo(true);
        String services = JSON.writeValueAsString(request.get("services"));
        assertThat(services).contains("\"serviceKey\":\"crm\"").contains("/leads")
                .doesNotContain("crm-secret").doesNotContain("https://crm.example");
        assertThat(releases.list(TENANT, "order")).singleElement()
                .satisfies(release -> assertThat(release.action()).isEqualTo(ReleaseAction.PUBLISH));
        assertThat(draftService.get("order").hasUnpublishedChanges()).isFalse();
    }

    @Test
    void refusesUnpublishedServicesAndUnchangedDrafts() {
        draftService.create(new CreateProcessDraftRequest("missing", "M", xml("missing", "nowhere")));
        assertThatThrownBy(() -> service.publish("missing", new PublishProcessRequest(1)))
                .hasMessageContaining("nowhere");
        assertThat(engine.requests).isEmpty();

        publishService("crm", "/leads");
        draftService.create(new CreateProcessDraftRequest("same", "S", xml("same", "crm")));
        service.publish("same", new PublishProcessRequest(1));
        assertThatThrownBy(() -> service.publish("same", new PublishProcessRequest(1)))
                .hasMessageContaining("giống bản đang chạy");

        publishService("crm", "/v2/leads");
        assertThat(service.publish("same", new PublishProcessRequest(1)).version()).isEqualTo(2);
        assertThatThrownBy(() -> service.publish("same", new PublishProcessRequest(7)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.DRAFT_REVISION_CONFLICT));
    }

    @Test
    void rollbackRedeploysTheBpmnAndServicesOfTheOldVersion() throws Exception {
        publishService("crm", "/v1");
        draftService.create(new CreateProcessDraftRequest("rb", "RB", xml("rb", "crm")));
        service.publish("rb", new PublishProcessRequest(1));
        publishService("crm", "/v2");
        draftService.save("rb", new SaveProcessDraftRequest("RB", xml("rb", "crm") + "\n", 1));
        service.publish("rb", new PublishProcessRequest(2));

        var v3 = service.rollback("rb", new RollbackProcessRequest(1));

        assertThat(v3.version()).isEqualTo(3);
        Map<String, Object> request = engine.requests.getLast();
        assertThat(request.get("copyAssignmentsFromVersion")).isEqualTo(1);
        assertThat(JSON.writeValueAsString(request.get("services"))).contains("/v1").doesNotContain("/v2");
        assertThat(releases.list(TENANT, "rb").getFirst().sourceVersion()).isEqualTo(1);
        assertThatThrownBy(() -> service.rollback("rb", new RollbackProcessRequest(3)))
                .hasMessageContaining("latest");
    }

    @Test
    void suspendResumeAndListMergeDraftsWithWhatTheEngineRuns() {
        publishService("crm", "/leads");
        draftService.create(new CreateProcessDraftRequest("live", "Live", xml("live", "crm")));
        service.publish("live", new PublishProcessRequest(1));
        draftService.create(new CreateProcessDraftRequest("draftOnly", "Draft", xml("draftOnly", "crm")));

        assertThat(service.suspend("live").action()).isEqualTo("SUSPEND");
        var page = service.page(1, 20, null, null, "all").records();
        assertThat(page).extracting(ProcessDefinitionSummaryView::processDefinitionKey,
                        ProcessDefinitionSummaryView::publishStatus)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("live", "suspended"),
                        org.assertj.core.groups.Tuple.tuple("draftOnly", "draft_only"));
        assertThat(service.resume("live").action()).isEqualTo("RESUME");
        assertThat(service.releases("live")).extracting("action").containsExactly("RESUME", "SUSPEND", "PUBLISH");
    }

    @Test
    void deleteRemovesTheProcessInTheEngineThenTheDraft() {
        publishService("crm", "/leads");
        draftService.create(new CreateProcessDraftRequest("gone", "Gone", xml("gone", "crm")));
        service.publish("gone", new PublishProcessRequest(1));

        service.delete("gone");

        assertThat(engine.deleted).containsExactly("gone");
        assertThat(drafts.find(TENANT, "gone")).isEmpty();
        assertThat(releases.list(TENANT, "gone")).isEmpty();
        assertThatThrownBy(() -> service.delete("gone")).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    private void publishService(String key, String path) {
        catalog.saveService(key, key);
        catalog.saveServiceSettings(key, key, null, List.of(new EnvironmentBaseUrl("default", "https://crm.example")),
                new AuthConfig(AuthType.BEARER, null, "crm-secret"));
        catalog.saveOperation(key, new PublishedOperation("op", HttpMethod.GET, path, AuthType.NONE, null, null,
                null, 1000, List.of("500"), "FAILED", List.of(), List.of()));
        catalog.publish(key);
    }

    private static String xml(String key, String serviceKey) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             xmlns:workflow="http://workflow-agent.local/bpmn" targetNamespace="t">
                  <process id="%s" name="%s" isExecutable="true">
                    <startEvent id="s"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="call"/>
                    <serviceTask id="call" flowable:delegateExpression="${serviceRegistryDelegate}">
                      <extensionElements>
                        <workflow:serviceCall serviceKey="%s" operationKey="op"/>
                      </extensionElements>
                    </serviceTask>
                    <sequenceFlow id="f2" sourceRef="call" targetRef="e"/>
                    <endEvent id="e"/>
                  </process>
                </definitions>""".formatted(key, key, serviceKey);
    }

    /** Keeps every deployment like Flow Engine would: versions per key, BPMN and service JSON per version. */
    private static final class FakeEngine extends FlowEngineClient {

        final List<Map<String, Object>> requests = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        private final Map<String, List<ProcessVersion>> versions = new LinkedHashMap<>();
        private final Map<String, Boolean> suspended = new LinkedHashMap<>();

        FakeEngine() {
            super(new FlowEngineProperties("http://unused", "flow-admin-be", "s", 1, null, null, null, null));
        }

        @Override
        @SuppressWarnings("unchecked")
        public Process deployProcess(Map<String, Object> request) {
            requests.add(request);
            String key = (String) request.get("processKey");
            List<ProcessVersion> list = versions.computeIfAbsent(key, ignored -> new ArrayList<>());
            Map<String, String> services = new TreeMap<>();
            for (Map<String, Object> service : (List<Map<String, Object>>) request.get("services")) {
                try {
                    services.put((String) service.get("serviceKey"), JSON.writeValueAsString(service));
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }
            int version = list.size() + 1;
            list.add(new ProcessVersion(key, (String) request.get("name"), version, key + ":" + version,
                    (String) request.get("bpmnXml"), services));
            suspended.put(key, false);
            return process(key).orElseThrow();
        }

        @Override
        public List<Process> processes() {
            return versions.keySet().stream().map(key -> process(key).orElseThrow()).toList();
        }

        @Override
        public Optional<Process> process(String key) {
            List<ProcessVersion> list = versions.get(key);
            if (list == null || list.isEmpty()) {
                return Optional.empty();
            }
            ProcessVersion latest = list.getLast();
            return Optional.of(new Process("dep-" + latest.processDefinitionId(), latest.processDefinitionId(), key,
                    latest.name(), latest.version(), suspended.get(key), OffsetDateTime.now(), null, List.of()));
        }

        @Override
        public ProcessVersion processVersion(String key, Integer version) {
            List<ProcessVersion> list = versions.get(key);
            return version == null ? list.getLast() : list.get(version - 1);
        }

        @Override
        public Process suspend(String key) {
            suspended.put(key, true);
            return process(key).orElseThrow();
        }

        @Override
        public Process resume(String key) {
            suspended.put(key, false);
            return process(key).orElseThrow();
        }

        @Override
        public void deleteProcess(String key) {
            deleted.add(key);
            versions.remove(key);
        }
    }
}
