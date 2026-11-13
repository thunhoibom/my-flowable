package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.process.application.WorkflowDefinitionService;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionView;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Read side of process definitions. The running version of a key is the latest
 * definition in the tenant that is not suspended.
 */
@Service
public class WorkflowDefinitionServiceImpl implements WorkflowDefinitionService {


    private final RepositoryService repositoryService;
    private final TenantProvider tenantProvider;
    private final ProcessDefinitionLookup lookup;

    public WorkflowDefinitionServiceImpl(RepositoryService repositoryService,
                                         TenantProvider tenantProvider,
                                         ProcessDefinitionLookup lookup) {
        this.repositoryService = repositoryService;
        this.tenantProvider = tenantProvider;
        this.lookup = lookup;
    }

    @Override
    public String runningProcessDefinitionId(String processDefinitionKey) {
        ProcessDefinition latest = lookup.latest(tenantProvider.current().tenantId(), processDefinitionKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Process definition does not exist"));
        if (latest.isSuspended()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Quy trình đã ngừng phát hành: " + processDefinitionKey);
        }
        return latest.getId();
    }

    @Override
    public boolean exists(String processDefinitionKey) {
        String tenantId = tenantProvider.current().tenantId();
        return lookup.anyVersion(tenantId, processDefinitionKey);
    }

    @Override
    public List<ProcessDefinitionView> list(String processDefinitionKey) {
        String tenantId = tenantProvider.current().tenantId();
        var query = repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenantId)
                .orderByProcessDefinitionKey()
                .asc()
                .orderByProcessDefinitionVersion()
                .desc();
        if (!StringUtils.hasText(processDefinitionKey)) {
            return query.latestVersion().list().stream()
                    .map(definition -> toView(definition, definition.getId(), false))
                    .toList();
        }
        List<ProcessDefinition> versions = query.processDefinitionKey(processDefinitionKey).list();
        String latestId = versions.isEmpty() ? null : versions.getFirst().getId();
        return versions.stream().map(definition -> toView(definition, latestId, false)).toList();
    }

    @Override
    public ProcessDefinitionView getDefinition(String processDefinitionKey, Integer version) {
        String tenantId = tenantProvider.current().tenantId();
        ProcessDefinition latest = lookup.latest(tenantId, processDefinitionKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Process definition does not exist"));
        ProcessDefinition definition = version == null
                ? latest
                : lookup.version(tenantId, processDefinitionKey, version)
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                                "Process definition does not exist"));
        return toView(definition, latest.getId(), true);
    }

    private ProcessDefinitionView toView(ProcessDefinition definition, String latestId, boolean includeXml) {
        boolean active = !definition.isSuspended() && definition.getId().equals(latestId);
        return new ProcessDefinitionView(
                definition.getId(),
                definition.getKey(),
                definition.getName(),
                definition.getVersion(),
                definition.getDeploymentId(),
                deploymentTime(definition.getDeploymentId()),
                definition.getTenantId(),
                active,
                includeXml ? lookup.xml(definition) : null
        );
    }

    private OffsetDateTime deploymentTime(String deploymentId) {
        Deployment deployment = repositoryService.createDeploymentQuery()
                .deploymentId(deploymentId)
                .singleResult();
        if (deployment == null || deployment.getDeploymentTime() == null) {
            return null;
        }
        return deployment.getDeploymentTime().toInstant().atOffset(ZoneOffset.UTC);
    }
}
