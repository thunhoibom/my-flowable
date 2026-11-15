package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.process.application.dto.FailedJobView;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.flowable.job.api.Job;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Dead-letter jobs on an in-memory Flowable engine, seen only by their tenant. */
class FlowableFailedJobServiceTest {

    private static final String TENANT = "tenant-jobs";
    private static final String OTHER = "tenant-other";
    private static ProcessEngine engine;

    private final WorkflowOperationAuditService audit = mock(WorkflowOperationAuditService.class);
    private FlowableFailedJobService service;
    private FlowableFailedJobService otherTenant;

    @BeforeAll
    static void start() {
        var configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setJdbcUrl("jdbc:h2:mem:failed-jobs;DB_CLOSE_DELAY=-1");
        configuration.setAsyncExecutorActivate(false);
        configuration.setCreateDiagramOnDeploy(false);
        engine = configuration.buildProcessEngine();
        for (String tenant : new String[] {TENANT, OTHER}) {
            engine.getRepositoryService().createDeployment().tenantId(tenant)
                    .addString("async.bpmn20.xml", """
                            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                         xmlns:flowable="http://flowable.org/bpmn"
                                         targetNamespace="test">
                              <process id="async-step" isExecutable="true">
                                <startEvent id="start"/>
                                <sequenceFlow id="f1" sourceRef="start" targetRef="call"/>
                                <serviceTask id="call" name="Gọi CRM" flowable:async="true" flowable:expression="${1}"/>
                                <sequenceFlow id="f2" sourceRef="call" targetRef="end"/>
                                <endEvent id="end"/>
                              </process>
                            </definitions>
                            """)
                    .deploy();
        }
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    @BeforeEach
    void setUp() {
        service = new FlowableFailedJobService(engine.getManagementService(),
                () -> new TenantContext.TenantInfo(TENANT, "jobs", "Jobs"), audit);
        otherTenant = new FlowableFailedJobService(engine.getManagementService(),
                () -> new TenantContext.TenantInfo(OTHER, "other", "Other"), audit);
    }

    @Test
    void listsDeadLetterJobsOfTheTenantAndRetriesThem() {
        String instanceId = failedJob(TENANT);
        failedJob(OTHER);

        PageResult<FailedJobView> page = service.page(1, 20, instanceId);
        assertThat(page.total()).isEqualTo(1);
        FailedJobView job = page.records().getFirst();
        assertThat(job.processDefinitionKey()).isEqualTo("async-step");
        assertThat(job.activityId()).isEqualTo("call");
        assertThat(job.activityName()).isEqualTo("Gọi CRM");
        assertThat(otherTenant.page(1, 20, instanceId).total()).isZero();
        assertThatThrownBy(() -> otherTenant.retry(job.jobId(), 3)).isInstanceOf(BusinessException.class);

        service.retry(job.jobId(), 2);

        Job executable = engine.getManagementService().createJobQuery().processInstanceId(instanceId).singleResult();
        assertThat(executable.getRetries()).isEqualTo(2);
        engine.getManagementService().executeJob(executable.getId());
        assertThat(engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(instanceId).count())
                .isZero();
        verify(audit).record(eq("JOB_RETRIED"), eq(instanceId), eq("async-step"), any(), eq(job.jobId()),
                eq("DEAD_LETTER"), eq("EXECUTABLE"), eq("retries=2"));
    }

    @Test
    void deletesAJobAndRejectsBadRetries() {
        String instanceId = failedJob(TENANT);
        String jobId = service.page(1, 20, instanceId).records().getFirst().jobId();

        assertThatThrownBy(() -> service.retry(jobId, 0)).hasMessageContaining("retries");
        assertThatThrownBy(() -> otherTenant.delete(jobId)).isInstanceOf(BusinessException.class);
        service.delete(jobId);

        assertThat(service.page(1, 20, instanceId).total()).isZero();
    }

    /** Starts the process and sends its async job straight to the dead-letter table. */
    private static String failedJob(String tenant) {
        String instanceId = engine.getRuntimeService()
                .startProcessInstanceByKeyAndTenantId("async-step", tenant).getId();
        Job job = engine.getManagementService().createJobQuery().processInstanceId(instanceId).singleResult();
        engine.getManagementService().moveJobToDeadLetterJob(job.getId());
        return instanceId;
    }
}
