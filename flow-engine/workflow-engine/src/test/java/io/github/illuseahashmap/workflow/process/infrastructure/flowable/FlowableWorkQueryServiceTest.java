package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.illuseahashmap.workflow.process.application.dto.InboxTaskView;
import io.github.illuseahashmap.workflow.process.application.dto.MyProcessView;
import io.github.illuseahashmap.workflow.process.application.dto.StartableProcessView;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Work area reads on an in-memory Flowable engine: only the current user's work, only in his tenant. */
class FlowableWorkQueryServiceTest {

    private static final String TENANT = "tenant-work";
    private static final String OTHER = "tenant-other";
    private static ProcessEngine engine;

    @BeforeAll
    static void start() {
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:work-query;DB_CLOSE_DELAY=-1");
        configuration.setAsyncExecutorActivate(false);
        configuration.setCreateDiagramOnDeploy(false);
        engine = configuration.buildProcessEngine();
        for (String tenant : new String[] {TENANT, OTHER}) {
            deploy(tenant, "approval", "Duyệt chi", """
                    <userTask id="approve" name="Duyệt" flowable:assignee="${approver}"/>""");
            deploy(tenant, "review", "Xem xét", """
                    <userTask id="check" name="Xem xét" flowable:candidateUsers="bob"
                              flowable:candidateGroups="WF_OPERATOR"/>""");
        }
        deploy(TENANT, "paused", "Tạm ngừng", "<userTask id=\"t\"/>");
        engine.getRepositoryService().suspendProcessDefinitionByKey("paused", TENANT);
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    @Test
    void theInboxHasTheTasksAssignedToMeAndThoseICanClaim() {
        String mine = startAs("carol", TENANT, "approval", "EXP-1", Map.of("approver", "alice"));
        startAs("carol", TENANT, "approval", "EXP-2", Map.of("approver", "dave"));
        String claimable = startAs("carol", TENANT, "review", "REV-1", Map.of());
        startAs("carol", OTHER, "approval", "EXP-9", Map.of("approver", "alice"));

        List<InboxTaskView> alice = service("alice", TENANT).inbox(1, 20, null).records();
        assertThat(alice).extracting(InboxTaskView::processInstanceId).containsExactly(mine);
        InboxTaskView task = alice.getFirst();
        assertThat(task.processDefinitionName()).isEqualTo("Duyệt chi");
        assertThat(task.businessKey()).isEqualTo("EXP-1");
        assertThat(task.assignee()).isEqualTo("alice");

        assertThat(service("bob", TENANT).inbox(1, 20, null).records())
                .extracting(InboxTaskView::processInstanceId).containsExactly(claimable);
        assertThat(service("bob", TENANT).inbox(1, 20, "rev").total()).isEqualTo(1);
        assertThat(service("bob", TENANT).inbox(1, 20, "nothing").total()).isZero();
        // A candidate group is not enough: Flow Engine only lets candidate users act (TaskViewAssembler.canOperate).
        assertThat(service("erin", TENANT).inbox(1, 20, null).total()).isZero();
    }

    @Test
    void myProcessesAreTheOnesIStartedInThisTenant() {
        String running = startAs("frank", TENANT, "review", "F-1", Map.of());
        String ended = startAs("frank", TENANT, "review", "F-2", Map.of());
        engine.getRuntimeService().deleteProcessInstance(ended, "huỷ");
        startAs("frank", OTHER, "review", "F-3", Map.of());
        startAs("gina", TENANT, "review", "G-1", Map.of());

        List<MyProcessView> all = service("frank", TENANT).myProcesses(1, 20, "all").records();
        assertThat(all).extracting(MyProcessView::processInstanceId).containsExactlyInAnyOrder(running, ended);
        assertThat(all).filteredOn(view -> view.processInstanceId().equals(ended))
                .extracting(MyProcessView::status).containsExactly("TERMINATED");
        assertThat(service("frank", TENANT).myProcesses(1, 20, "running").records())
                .extracting(MyProcessView::processInstanceId).containsExactly(running);
        assertThatThrownBy(() -> service("frank", TENANT).myProcesses(1, 20, "odd"))
                .hasMessageContaining("Unsupported status");
    }

    @Test
    void startableIsTheRunningVersionOfEachProcessButNotTheSuspendedOnes() {
        deploy(TENANT, "review", "Xem xét v2", """
                <userTask id="check" name="Xem xét" flowable:candidateUsers="bob"/>""");

        List<StartableProcessView> startable = service("alice", TENANT).startable();

        assertThat(startable).extracting(StartableProcessView::processDefinitionKey)
                .containsExactlyInAnyOrder("approval", "review");
        assertThat(startable).filteredOn(view -> view.processDefinitionKey().equals("review"))
                .extracting(StartableProcessView::processDefinitionName).containsExactly("Xem xét v2");
    }

    private static FlowableWorkQueryService service(String username, String tenant) {
        return new FlowableWorkQueryService(engine.getTaskService(), engine.getRuntimeService(),
                engine.getHistoryService(), engine.getRepositoryService(),
                () -> new CurrentPrincipal("USER", username + "-id", username, username, tenant,
                        Set.of("WF_OPERATOR"), Set.of()),
                () -> new TenantContext.TenantInfo(tenant, tenant, tenant));
    }

    private static String startAs(String username, String tenant, String key, String businessKey,
                                  Map<String, Object> variables) {
        engine.getIdentityService().setAuthenticatedUserId(username);
        try {
            return engine.getRuntimeService()
                    .startProcessInstanceByKeyAndTenantId(key, businessKey, variables, tenant).getId();
        } finally {
            engine.getIdentityService().setAuthenticatedUserId(null);
        }
    }

    private static void deploy(String tenant, String key, String name, String task) {
        engine.getRepositoryService().createDeployment().tenantId(tenant)
                .addString(key + ".bpmn20.xml", """
                        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                     xmlns:flowable="http://flowable.org/bpmn" targetNamespace="test">
                          <process id="%s" name="%s" isExecutable="true">
                            <startEvent id="start"/>
                            <sequenceFlow id="f1" sourceRef="start" targetRef="%s"/>
                            %s
                            <sequenceFlow id="f2" sourceRef="%s" targetRef="end"/>
                            <endEvent id="end"/>
                          </process>
                        </definitions>
                        """.formatted(key, name, taskId(task), task, taskId(task)))
                .deploy();
    }

    private static String taskId(String task) {
        int start = task.indexOf("id=\"") + 4;
        return task.substring(start, task.indexOf('"', start));
    }
}
