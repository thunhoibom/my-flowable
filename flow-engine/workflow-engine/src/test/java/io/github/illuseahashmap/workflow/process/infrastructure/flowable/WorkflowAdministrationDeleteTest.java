package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.illuseahashmap.workflow.eventregistry.application.port.EventReferenceRepository;
import io.github.illuseahashmap.workflow.assignment.application.AssignmentRuleService;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.process.infrastructure.lock.ProcessInstanceTransactionExecutor;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class WorkflowAdministrationDeleteTest {

    private static final String TENANT = "tenant-delete";
    private static ProcessEngine engine;

    private InMemoryProcessRepositories.References callReferences;
    private AssignmentRuleService rules;
    private WorkflowAdministrationServiceImpl service;

    @BeforeAll
    static void start() {
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:admin-delete;DB_CLOSE_DELAY=-1");
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
        callReferences = new InMemoryProcessRepositories.References();
        rules = mock(AssignmentRuleService.class);
        service = new WorkflowAdministrationServiceImpl(engine.getRepositoryService(), engine.getRuntimeService(),
                engine.getHistoryService(), mock(JdbcTemplate.class), mock(ProcessInstanceTransactionExecutor.class),
                null, null, () -> new TenantContext.TenantInfo(TENANT, "del", "Del"), rules,
                mock(WorkflowOperationAuditService.class), callReferences,
                mock(EventReferenceRepository.class));
    }

    private static String userTaskXml(String key) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
                  <process id="%s" isExecutable="true">
                    <startEvent id="s"/><userTask id="a"/><endEvent id="e"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="a"/><sequenceFlow id="f2" sourceRef="a" targetRef="e"/>
                  </process>
                </definitions>""".formatted(key);
    }

    private static ProcessDefinition deploy(String key) {
        var deployment = engine.getRepositoryService().createDeployment().tenantId(TENANT)
                .addString(key + ".bpmn20.xml", userTaskXml(key)).deploy();
        return engine.getRepositoryService().createProcessDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult();
    }

    private static long count(String key) {
        return engine.getRepositoryService().createProcessDefinitionQuery()
                .processDefinitionTenantId(TENANT).processDefinitionKey(key).count();
    }

    @Test
    void deleteVersionRejectsTheLatest() {
        deploy("x1");
        deploy("x1");

        assertThatThrownBy(() -> service.deleteProcessDefinitionVersion("x1", 2))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST))
                .hasMessageContaining("ngừng phát hành");
    }

    @Test
    void deleteVersionRejectsRunningInstances() {
        ProcessDefinition v1 = deploy("x2");
        deploy("x2");
        engine.getRuntimeService().startProcessInstanceById(v1.getId());

        assertThatThrownBy(() -> service.deleteProcessDefinitionVersion("x2", 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(count("x2")).isEqualTo(2);
    }

    @Test
    void deleteVersionRemovesAnIdleOldVersion() {
        ProcessDefinition v1 = deploy("x3");
        deploy("x3");

        service.deleteProcessDefinitionVersion("x3", 1);

        assertThat(count("x3")).isEqualTo(1);
        verify(rules).deleteByProcessDefinition(v1.getId());
    }

    @Test
    void deleteKeyRejectsRunningInstances() {
        ProcessDefinition v1 = deploy("x4");
        engine.getRuntimeService().startProcessInstanceById(v1.getId());

        assertThatThrownBy(() -> service.deleteProcessDefinitions("x4"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(count("x4")).isEqualTo(1);
    }

    @Test
    void deleteKeyRemovesEveryVersionAndItsRules() {
        deploy("x5");
        deploy("x5");

        service.deleteProcessDefinitions("x5");

        assertThat(count("x5")).isZero();
        verify(rules).deleteByProcessDefinitionKey("x5");
    }

    @Test
    void deleteKeyRejectsAProcessThatAPublishedProcessCalls() {
        deploy("x7");
        ProcessDefinition caller = deploy("x8");
        callReferences.replaceForDefinition(TENANT, caller.getId(), java.util.List.of(
                new io.github.illuseahashmap.workflow.process.domain.CallReference(
                        caller.getId(), "x8", caller.getVersion(), "call", "x7")));

        assertThatThrownBy(() -> service.deleteProcessDefinitions("x7"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT))
                .hasMessageContaining("x8 v1");

        engine.getRepositoryService().suspendProcessDefinitionById(caller.getId(), false, null);
        service.deleteProcessDefinitions("x7");
        assertThat(count("x7")).isZero();
    }

    @Test
    void deleteKeyOfANeverDeployedProcessIsNotFound() {
        assertThatThrownBy(() -> service.deleteProcessDefinitions("x6"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
