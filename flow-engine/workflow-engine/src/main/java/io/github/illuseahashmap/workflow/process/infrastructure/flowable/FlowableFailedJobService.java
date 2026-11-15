package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.process.application.FailedJobService;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.process.application.dto.FailedJobView;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import org.flowable.engine.ManagementService;
import org.flowable.job.api.DeadLetterJobQuery;
import org.flowable.job.api.Job;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class FlowableFailedJobService implements FailedJobService {

    private static final int MAX_RETRIES = 10;

    private final ManagementService managementService;
    private final TenantProvider tenantProvider;
    private final WorkflowOperationAuditService auditService;

    public FlowableFailedJobService(ManagementService managementService, TenantProvider tenantProvider,
                                    WorkflowOperationAuditService auditService) {
        this.managementService = managementService;
        this.tenantProvider = tenantProvider;
        this.auditService = auditService;
    }

    @Override
    public PageResult<FailedJobView> page(Integer pageNum, Integer pageSize, String processInstanceId) {
        int page = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        DeadLetterJobQuery query = managementService.createDeadLetterJobQuery()
                .jobTenantId(tenantProvider.current().tenantId());
        if (StringUtils.hasText(processInstanceId)) {
            query.processInstanceId(processInstanceId.trim());
        }
        long total = query.count();
        List<FailedJobView> records = query.orderByJobCreateTime().desc()
                .listPage((page - 1) * size, size).stream().map(FlowableFailedJobService::toView).toList();
        return new PageResult<>(total, page, size, records);
    }

    @Override
    public String stacktrace(String jobId) {
        job(jobId);
        return managementService.getDeadLetterJobExceptionStacktrace(jobId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void retry(String jobId, int retries) {
        if (retries < 1 || retries > MAX_RETRIES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "retries phải từ 1 đến " + MAX_RETRIES);
        }
        Job job = job(jobId);
        managementService.moveDeadLetterJobToExecutableJob(jobId, retries);
        auditService.record("JOB_RETRIED", job.getProcessInstanceId(), key(job.getProcessDefinitionId()),
                null, jobId, "DEAD_LETTER", "EXECUTABLE", "retries=" + retries);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String jobId) {
        Job job = job(jobId);
        managementService.deleteDeadLetterJob(jobId);
        auditService.record("JOB_DELETED", job.getProcessInstanceId(), key(job.getProcessDefinitionId()),
                null, jobId, "DEAD_LETTER", "DELETED", null);
    }

    /** The job, only when it belongs to the current tenant. */
    private Job job(String jobId) {
        Job job = managementService.createDeadLetterJobQuery()
                .jobId(jobId).jobTenantId(tenantProvider.current().tenantId()).singleResult();
        if (job == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Không tìm thấy job lỗi " + jobId);
        }
        return job;
    }

    private static FailedJobView toView(Job job) {
        return new FailedJobView(job.getId(), job.getJobType(), job.getProcessInstanceId(),
                job.getProcessDefinitionId(), key(job.getProcessDefinitionId()), job.getExecutionId(),
                job.getElementId(), job.getElementName(), job.getExceptionMessage(),
                time(job.getCreateTime()), time(job.getDuedate()));
    }

    /** Flowable process definition ids are {@code key:version:id}; a BPMN id cannot contain ':'. */
    private static String key(String processDefinitionId) {
        if (processDefinitionId == null) {
            return null;
        }
        int colon = processDefinitionId.indexOf(':');
        return colon < 0 ? processDefinitionId : processDefinitionId.substring(0, colon);
    }

    private static OffsetDateTime time(Date date) {
        return date == null ? null : date.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}
