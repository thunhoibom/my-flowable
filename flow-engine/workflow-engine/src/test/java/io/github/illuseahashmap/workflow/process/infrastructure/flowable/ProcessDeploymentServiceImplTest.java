package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.assignment.application.AssignmentRuleService;
import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentRuleCommand;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentType;
import io.github.illuseahashmap.workflow.config.EventRegistryProperties;
import io.github.illuseahashmap.workflow.config.ProcessDefinitionProperties;
import io.github.illuseahashmap.workflow.config.ScriptTaskProperties;
import io.github.illuseahashmap.workflow.config.SubProcessProperties;
import io.github.illuseahashmap.workflow.eventregistry.application.port.EventReferenceRepository;
import io.github.illuseahashmap.workflow.eventregistry.application.port.EventRegistryGateway;
import io.github.illuseahashmap.workflow.process.application.AgentBindingDeploymentValidator;
import io.github.illuseahashmap.workflow.process.application.WorkflowAdministrationService;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.process.application.dto.DeploymentAssignmentRule;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDeploymentRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDeploymentResult;
import io.github.illuseahashmap.workflow.process.application.port.AgentTaskBindingParser;
import io.github.illuseahashmap.workflow.process.application.port.ServiceDeploymentSupport;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.subprocess.CallActivityDeploymentGuard;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The receiving side of {@code POST /api/deployment/processes}, on an in-memory Flowable engine. */
class ProcessDeploymentServiceImplTest {

    private static final String TENANT = "tenant-deploy";

    private static ProcessEngine engine;
    private AssignmentRuleService rules;
    private final Set<String> configured = new HashSet<>();
    private ProcessDeploymentServiceImpl service;

    @BeforeAll
    static void start() {
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:deployment-service;DB_CLOSE_DELAY=-1");
        configuration.setAsyncExecutorActivate(false);
        configuration.setCreateDiagramOnDeploy(false);
        engine = configuration.buildProcessEngine();
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    @BeforeEach
    void setUp() {
        TenantProvider tenant = () -> new TenantContext.TenantInfo(TENANT, "dep", "Dep");
        var lookup = new ProcessDefinitionLookup(engine.getRepositoryService());
        var inspector = new BpmnDraftInspector(new ProcessDefinitionProperties());
        var callReferences = new InMemoryProcessRepositories.References();
        SubProcessProperties subProcessProperties = new SubProcessProperties();
        var deployer = new ProcessDeployer(engine.getRepositoryService(), inspector, new ScriptTaskDeploymentGuard(new ScriptTaskProperties()),
                new SubProcessDeploymentGuard(subProcessProperties),
                new CallActivityDeploymentGuard(subProcessProperties, engine.getRepositoryService(), lookup,
                        callReferences, mock(FlowableParticipantAssignmentCoordinator.class)),
                new EventRegistryDeploymentGuard(new EventRegistryProperties(), mock(EventRegistryGateway.class)),
                callReferences, mock(EventReferenceRepository.class), mock(AgentTaskBindingParser.class),
                mock(AgentBindingDeploymentValidator.class), new XmlServiceTaskBindingParser());
        rules = mock(AssignmentRuleService.class);
        ServiceDeploymentSupport serviceSupport = new ServiceDeploymentSupport() {
            @Override
            public byte[] serviceResource(String serviceKey, Map<String, Object> definition) {
                if (!serviceKey.equals(definition.get("serviceKey"))) {
                    throw new IllegalArgumentException("serviceKey không khớp");
                }
                return ("{\"serviceKey\":\"" + serviceKey + "\",\"v\":" + definition.get("v") + "}")
                        .getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public Set<String> missingConfiguration(String tenantId, Map<String, byte[]> services) {
                Set<String> missing = new TreeSet<>();
                services.keySet().stream().filter(serviceKey -> !configured.contains(serviceKey))
                        .forEach(serviceKey -> missing.add("secret " + serviceKey + "Token"));
                return missing;
            }
        };
        service = new ProcessDeploymentServiceImpl(engine.getRepositoryService(), deployer, lookup, serviceSupport,
                (tenantId, publishId) -> { }, rules, mock(WorkflowAdministrationService.class),
                mock(WorkflowOperationAuditService.class), tenant);
    }

    @Test
    void deploysBpmnWithTheServicesItCallsAndUsesThePublishIdAsDeploymentKey() {
        String key = key();
        configured.add("crm");
        String publishId = UUID.randomUUID().toString();

        ProcessDeploymentResult result = service.deploy(request(publishId, key, serviceTaskXml(key, "crm"),
                List.of(Map.of("serviceKey", "crm", "v", 1), Map.of("serviceKey", "unused", "v", 1)), List.of()));

        assertThat(result.version()).isEqualTo(1);
        assertThat(result.processKey()).isEqualTo(key);
        var deployment = engine.getRepositoryService().createDeploymentQuery()
                .deploymentId(result.deploymentId()).singleResult();
        assertThat(deployment.getKey()).isEqualTo(publishId);
        assertThat(deployment.getTenantId()).isEqualTo(TENANT);
        assertThat(engine.getRepositoryService().getDeploymentResourceNames(result.deploymentId()))
                .contains("crm.service").doesNotContain("unused.service");
        assertThat(resource(result.deploymentId(), "crm.service")).contains("\"v\":1");
    }

    @Test
    void sendingTheSamePublishIdAgainReturnsTheFirstVersion() {
        String key = key();
        String publishId = UUID.randomUUID().toString();
        ProcessDeploymentResult first = service.deploy(request(publishId, key, userTaskXml(key), List.of(), List.of()));

        ProcessDeploymentResult again = service.deploy(request(publishId, key, userTaskXml(key), List.of(), List.of()));

        assertThat(again.deploymentId()).isEqualTo(first.deploymentId());
        assertThat(again.processDefinitionId()).isEqualTo(first.processDefinitionId());
        assertThat(again.version()).isEqualTo(first.version());
        assertThat(versions(key)).isEqualTo(1);
        assertThatThrownBy(() -> service.deploy(request(publishId, key(), userTaskXml(key()), List.of(), List.of())))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void rejectsServicesMissingFromTheRequestOrTheirConfiguration() {
        String key = key();
        configured.add("crm");

        assertThatThrownBy(() -> service.deploy(request(UUID.randomUUID().toString(), key,
                serviceTaskXml(key, "crm"), List.of(), List.of())))
                .hasMessageContaining("Thiếu định nghĩa").hasMessageContaining("crm");
        assertThatThrownBy(() -> service.deploy(request(UUID.randomUUID().toString(), key,
                serviceTaskXml(key, "erp"), List.of(Map.of("serviceKey", "erp", "v", 1)), List.of())))
                .hasMessageContaining("thiếu cấu hình").hasMessageContaining("secret erpToken");
        assertThatThrownBy(() -> service.deploy(request(UUID.randomUUID().toString(), key,
                serviceTaskXml(key, "crm"), List.of(Map.of("serviceKey", "other", "v", 1),
                        Map.of("v", 1)), List.of())))
                .hasMessageContaining("serviceKey");
        assertThat(versions(key)).isZero();
    }

    @Test
    void writesTheAssignmentRulesForTheNewDefinition() {
        String key = key();
        DeploymentAssignmentRule rule = new DeploymentAssignmentRule("approve", 10, AssignmentType.CANDIDATE_GROUPS,
                null, null, List.of("managers"), null, null, null, true, "Quản lý duyệt", List.of());

        ProcessDeploymentResult result = service.deploy(request(UUID.randomUUID().toString(), key, userTaskXml(key),
                List.of(), List.of(rule)));

        var command = org.mockito.ArgumentCaptor.forClass(AssignmentRuleCommand.class);
        verify(rules).create(command.capture());
        assertThat(command.getValue().processDefinitionId()).isEqualTo(result.processDefinitionId());
        assertThat(command.getValue().taskDefinitionKey()).isEqualTo("approve");
        assertThat(command.getValue().candidateGroups()).containsExactly("managers");
    }

    @Test
    void anInvalidAssignmentRuleRejectsTheRequest() {
        String key = key();
        when(rules.create(any())).thenThrow(new BusinessException(ErrorCode.BAD_REQUEST, "Task approve requires"));
        DeploymentAssignmentRule rule = new DeploymentAssignmentRule("approve", null, AssignmentType.ASSIGNEE,
                List.of("bob"), null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.deploy(request(UUID.randomUUID().toString(), key, userTaskXml(key),
                List.of(), List.of(rule)))).hasMessageContaining("requires");
    }

    @Test
    void rejectsAnotherTenant() {
        String key = key();
        ProcessDeploymentRequest request = new ProcessDeploymentRequest(UUID.randomUUID().toString(), "other", key,
                key, userTaskXml(key), List.of(), List.of(), null, null);

        assertThatThrownBy(() -> service.deploy(request)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(rules, never()).create(any());
    }

    @Test
    void suspendResumeAndLatest() {
        String key = key();
        assertThat(service.latest(key)).isEmpty();
        service.deploy(request(UUID.randomUUID().toString(), key, userTaskXml(key), List.of(), List.of()));

        assertThat(service.suspend(key).suspended()).isTrue();
        assertThat(service.latest(key).orElseThrow().suspended()).isTrue();
        assertThatThrownBy(() -> service.suspend(key)).hasMessageContaining("already suspended");
        assertThat(service.resume(key).suspended()).isFalse();
        assertThatThrownBy(() -> service.resume(key)).hasMessageContaining("not suspended");
        assertThatThrownBy(() -> service.suspend(key())).hasMessageContaining("no published version");
    }

    private static ProcessDeploymentRequest request(String publishId, String key, String xml,
                                                    List<Map<String, Object>> services,
                                                    List<DeploymentAssignmentRule> assignments) {
        return new ProcessDeploymentRequest(publishId, TENANT, key, key, xml, services, assignments, null, null);
    }

    private static String key() {
        return "p" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static long versions(String key) {
        return engine.getRepositoryService().createProcessDefinitionQuery()
                .processDefinitionTenantId(TENANT).processDefinitionKey(key).count();
    }

    private static String resource(String deploymentId, String name) {
        try (InputStream content = engine.getRepositoryService().getResourceAsStream(deploymentId, name)) {
            return new String(content.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String userTaskXml(String key) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
                  <process id="%s" name="%s" isExecutable="true">
                    <startEvent id="s"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="approve"/>
                    <userTask id="approve"/>
                    <sequenceFlow id="f2" sourceRef="approve" targetRef="e"/>
                    <endEvent id="e"/>
                  </process>
                </definitions>""".formatted(key, key);
    }

    private static String serviceTaskXml(String key, String serviceKey) {
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
}
