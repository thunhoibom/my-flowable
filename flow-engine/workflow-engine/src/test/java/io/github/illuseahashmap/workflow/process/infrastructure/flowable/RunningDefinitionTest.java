package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionSummaryView;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.Set;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** "Running" means the latest definition of the key that is not suspended. */
class RunningDefinitionTest {

    private static final String TENANT = "tenant-running";
    private static final TenantProvider TENANT_PROVIDER = () -> new TenantContext.TenantInfo(TENANT, "run", "Run");

    private static ProcessEngine engine;
    private static WorkflowDefinitionServiceImpl definitions;
    private static WorkflowTaskOperationSupport support;
    private static WorkflowDefinitionReadService reads;

    @BeforeAll
    static void start() {
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:running;DB_CLOSE_DELAY=-1");
        configuration.setAsyncExecutorActivate(false);
        configuration.setCreateDiagramOnDeploy(false);
        engine = configuration.buildProcessEngine();
        var lookup = new ProcessDefinitionLookup(engine.getRepositoryService());
        definitions = new WorkflowDefinitionServiceImpl(engine.getRepositoryService(), TENANT_PROVIDER, lookup);
        support = new WorkflowTaskOperationSupport(engine.getRuntimeService(), engine.getTaskService(),
                engine.getRepositoryService(), definitions, null, null, TENANT_PROVIDER);
        reads = new WorkflowDefinitionReadService(engine.getRepositoryService(), TENANT_PROVIDER, lookup);
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    private static String xml(String key) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
                  <process id="%s" name="%s" isExecutable="true">
                    <startEvent id="s"/><endEvent id="e"/><sequenceFlow id="f" sourceRef="s" targetRef="e"/>
                  </process>
                </definitions>""".formatted(key, key);
    }

    private static ProcessDefinition deploy(String key) {
        var deployment = engine.getRepositoryService().createDeployment().tenantId(TENANT)
                .addString(key + ".bpmn20.xml", xml(key)).deploy();
        return engine.getRepositoryService().createProcessDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult();
    }

    @Test
    void startByKeyUsesLatestAndRejectsSuspended() {
        deploy("r1");
        ProcessDefinition v2 = deploy("r1");

        assertThat(support.resolveProcessDefinitionId("r1", null, TENANT)).isEqualTo(v2.getId());

        engine.getRepositoryService().suspendProcessDefinitionById(v2.getId(), false, null);
        assertThatThrownBy(() -> support.resolveProcessDefinitionId("r1", null, TENANT))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void startByIdAcceptsOnlyTheLatest() {
        ProcessDefinition v1 = deploy("r2");
        ProcessDefinition v2 = deploy("r2");

        assertThat(support.resolveProcessDefinitionId("r2", v2.getId(), TENANT)).isEqualTo(v2.getId());
        assertThatThrownBy(() -> support.resolveProcessDefinitionId("r2", v1.getId(), TENANT))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void activeFlagFollowsLatestAndSuspension() {
        deploy("r3");
        ProcessDefinition v2 = deploy("r3");

        assertThat(definitions.list("r3")).extracting("version", "active")
                .containsExactly(tuple(2, true), tuple(1, false));

        engine.getRepositoryService().suspendProcessDefinitionById(v2.getId(), false, null);
        assertThat(definitions.list("r3").getFirst().active()).isFalse();
    }

    @Test
    void pageShowsPublishedAndSuspendedKeys() {
        deploy("r4");
        ProcessDefinition stopped = deploy("r5");
        engine.getRepositoryService().suspendProcessDefinitionById(stopped.getId(), false, null);

        var page = reads.page(1, 100, "r", null, "all").records().stream()
                .filter(row -> Set.of("r4", "r5").contains(row.processDefinitionKey()))
                .toList();

        assertThat(page).extracting(ProcessDefinitionSummaryView::processDefinitionKey,
                        ProcessDefinitionSummaryView::publishStatus)
                .containsExactlyInAnyOrder(tuple("r4", "published"), tuple("r5", "suspended"));
        assertThat(reads.page(1, 100, "r5", null, "draft_only").records()).isEmpty();
        assertThat(reads.page(1, 100, "r5", null, "unpublished").records()).hasSize(1);
        assertThat(definitions.exists("r4")).isTrue();
        assertThat(definitions.exists("r6")).isFalse();
    }
}
