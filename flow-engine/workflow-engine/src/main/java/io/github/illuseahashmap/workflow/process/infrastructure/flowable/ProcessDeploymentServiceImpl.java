package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.assignment.application.AssignmentRuleService;
import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentRuleCommand;
import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentRuleInheritResult;
import io.github.illuseahashmap.workflow.process.application.ProcessDeploymentService;
import io.github.illuseahashmap.workflow.process.application.WorkflowAdministrationService;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.process.application.dto.DeploymentAssignmentRule;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDeploymentCommand;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDeploymentRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDeploymentResult;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessVersionContent;
import io.github.illuseahashmap.workflow.process.application.port.PublishLock;
import io.github.illuseahashmap.workflow.process.application.port.ServiceDeploymentSupport;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ProcessDeploymentServiceImpl implements ProcessDeploymentService {

    private final RepositoryService repositoryService;
    private final ProcessDeployer deployer;
    private final ProcessDefinitionLookup lookup;
    private final ServiceDeploymentSupport serviceSupport;
    private final PublishLock publishLock;
    private final AssignmentRuleService assignmentRules;
    private final WorkflowAdministrationService administration;
    private final WorkflowOperationAuditService auditService;
    private final TenantProvider tenantProvider;

    public ProcessDeploymentServiceImpl(RepositoryService repositoryService, ProcessDeployer deployer,
                                        ProcessDefinitionLookup lookup, ServiceDeploymentSupport serviceSupport,
                                        PublishLock publishLock, AssignmentRuleService assignmentRules,
                                        WorkflowAdministrationService administration,
                                        WorkflowOperationAuditService auditService, TenantProvider tenantProvider) {
        this.repositoryService = repositoryService;
        this.deployer = deployer;
        this.lookup = lookup;
        this.serviceSupport = serviceSupport;
        this.publishLock = publishLock;
        this.assignmentRules = assignmentRules;
        this.administration = administration;
        this.auditService = auditService;
        this.tenantProvider = tenantProvider;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProcessDeploymentResult deploy(ProcessDeploymentRequest request) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        if (StringUtils.hasText(request.tenantId()) && !request.tenantId().equals(tenant.tenantId())
                && !request.tenantId().equals(tenant.tenantCode())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "tenantId does not match the tenant of the caller");
        }
        Map<String, byte[]> services = new TreeMap<>();
        for (Map<String, Object> service : request.services() == null ? List.<Map<String, Object>>of()
                : request.services()) {
            Object serviceKey = service == null ? null : service.get("serviceKey");
            if (!(serviceKey instanceof String key) || !StringUtils.hasText(key)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Mỗi dịch vụ phải có serviceKey");
            }
            if (services.containsKey(key)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Dịch vụ " + key + " bị gửi hai lần");
            }
            try {
                services.put(key, serviceSupport.designOnly(key, service));
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "Dịch vụ " + key + " không hợp lệ: " + exception.getMessage());
            }
        }
        return deploy(new ProcessDeploymentCommand(request.publishId(), request.processKey(), request.name(),
                request.bpmnXml(), services, request.assignments(),
                Boolean.TRUE.equals(request.copyAssignmentsFromLatest()), request.copyAssignmentsFromVersion()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProcessDeploymentResult deploy(ProcessDeploymentCommand command) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        String publishId = required(command.publishId(), "publishId");
        String key = required(command.processKey(), "processKey");
        publishLock.lock(tenant.tenantId(), publishId);
        Optional<ProcessDeploymentResult> previous = alreadyDeployed(tenant.tenantId(), publishId, key);
        if (previous.isPresent()) {
            return previous.get();
        }
        Set<String> withoutEnvironment = serviceSupport.withoutEnvironment(
                tenant.tenantId(), deployer.serviceKeys(command.bpmnXml()));
        if (!withoutEnvironment.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Các dịch vụ chưa có cấu hình môi trường trên Flow Engine này: "
                            + String.join(", ", withoutEnvironment));
        }
        ProcessDefinition previousLatest = lookup.latest(tenant.tenantId(), key).orElse(null);
        ProcessDefinition rulesSource = rulesSource(tenant.tenantId(), key, command, previousLatest);
        ProcessDefinition definition = deployer.deployChecked(tenant, publishId, key, command.name(),
                command.bpmnXml(), command.services());
        for (DeploymentAssignmentRule rule : command.assignments()) {
            assignmentRules.create(new AssignmentRuleCommand(definition.getId(), rule.nodeId(), rule.priority(),
                    rule.assignmentType(), rule.assignees(), rule.candidateUsers(), rule.candidateGroups(),
                    rule.countersignUsers(), rule.emptyUserStrategy(), rule.fallbackAssignee(), rule.enabled(),
                    rule.description(), rule.conditions()));
        }
        AssignmentRuleInheritResult copied = rulesSource == null
                ? null : assignmentRules.copyRules(rulesSource.getId(), definition.getId());
        auditService.record("PROCESS_DEFINITION_DEPLOYED", null, key, null,
                "version=" + definition.getVersion() + ", publishId=" + publishId, null, "DEPLOYED", null);
        Set<String> newUserTasks = userTaskIds(definition.getId());
        if (previousLatest != null) {
            newUserTasks.removeAll(userTaskIds(previousLatest.getId()));
        }
        return new ProcessDeploymentResult(definition.getDeploymentId(), definition.getId(), definition.getKey(),
                definition.getName(), definition.getVersion(), false, deployTime(definition.getDeploymentId()),
                copied, List.copyOf(newUserTasks));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProcessDeploymentResult> list() {
        List<ProcessDefinition> definitions = repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenantProvider.current().tenantId()).latestVersion().list();
        Map<String, OffsetDateTime> times = new HashMap<>();
        List<String> deploymentIds = definitions.stream().map(ProcessDefinition::getDeploymentId).distinct().toList();
        if (!deploymentIds.isEmpty()) {
            repositoryService.createDeploymentQuery().deploymentIds(deploymentIds).list()
                    .forEach(deployment -> times.put(deployment.getId(), offset(deployment.getDeploymentTime())));
        }
        return definitions.stream()
                .map(definition -> result(definition, definition.isSuspended(),
                        times.get(definition.getDeploymentId())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProcessDeploymentResult> latest(String processKey) {
        return lookup.latest(tenantProvider.current().tenantId(), processKey)
                .map(definition -> result(definition, definition.isSuspended()));
    }

    @Override
    @Transactional(readOnly = true)
    public ProcessVersionContent version(String processKey, Integer version) {
        String tenantId = tenantProvider.current().tenantId();
        ProcessDefinition definition = (version == null ? lookup.latest(tenantId, processKey)
                : lookup.version(tenantId, processKey, version))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Process version does not exist"));
        Map<String, String> services = new TreeMap<>();
        deployer.serviceResources(definition.getDeploymentId()).forEach((serviceKey, content) ->
                services.put(serviceKey, new String(content, StandardCharsets.UTF_8)));
        return new ProcessVersionContent(definition.getKey(), definition.getName(), definition.getVersion(),
                definition.getId(), lookup.xml(definition), services);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProcessDeploymentResult suspend(String processKey) {
        ProcessDefinition latest = requireLatest(processKey);
        if (latest.isSuspended()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Process is already suspended");
        }
        repositoryService.suspendProcessDefinitionById(latest.getId(), false, null);
        auditService.record("PROCESS_DEFINITION_SUSPENDED", null, processKey, null,
                "version=" + latest.getVersion(), "PUBLISHED", "SUSPENDED", null);
        return result(latest, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProcessDeploymentResult resume(String processKey) {
        ProcessDefinition latest = requireLatest(processKey);
        if (!latest.isSuspended()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Process is not suspended");
        }
        repositoryService.activateProcessDefinitionById(latest.getId(), false, null);
        auditService.record("PROCESS_DEFINITION_RESUMED", null, processKey, null,
                "version=" + latest.getVersion(), "SUSPENDED", "PUBLISHED", null);
        return result(latest, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteVersion(String processKey, int version) {
        administration.deleteProcessDefinitionVersion(processKey, version);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteProcess(String processKey) {
        administration.deleteProcessDefinitions(processKey);
    }

    /** The version a publish id created before. Its deployment key is the publish id. */
    private Optional<ProcessDeploymentResult> alreadyDeployed(String tenantId, String publishId, String key) {
        List<Deployment> deployments = repositoryService.createDeploymentQuery()
                .deploymentKey(publishId).deploymentTenantId(tenantId).list();
        if (deployments.isEmpty()) {
            return Optional.empty();
        }
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployments.getFirst().getId()).processDefinitionTenantId(tenantId).singleResult();
        if (definition == null || !key.equals(definition.getKey())) {
            throw new BusinessException(ErrorCode.CONFLICT, "publishId " + publishId + " đã dùng cho quy trình khác");
        }
        return Optional.of(result(definition, definition.isSuspended()));
    }

    private ProcessDefinition rulesSource(String tenantId, String key, ProcessDeploymentCommand command,
                                          ProcessDefinition previousLatest) {
        if (command.copyAssignmentsFromVersion() != null) {
            return lookup.version(tenantId, key, command.copyAssignmentsFromVersion())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                            "Process version " + command.copyAssignmentsFromVersion() + " does not exist"));
        }
        return command.copyAssignmentsFromLatest() ? previousLatest : null;
    }

    private Set<String> userTaskIds(String definitionId) {
        return repositoryService.getBpmnModel(definitionId).getMainProcess()
                .findFlowElementsOfType(UserTask.class, true).stream()
                .map(UserTask::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private ProcessDefinition requireLatest(String processKey) {
        return lookup.latest(tenantProvider.current().tenantId(), processKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Process has no published version"));
    }

    private ProcessDeploymentResult result(ProcessDefinition definition, boolean suspended) {
        return result(definition, suspended, deployTime(definition.getDeploymentId()));
    }

    private static ProcessDeploymentResult result(ProcessDefinition definition, boolean suspended,
                                                  OffsetDateTime deployTime) {
        return new ProcessDeploymentResult(definition.getDeploymentId(), definition.getId(), definition.getKey(),
                definition.getName(), definition.getVersion(), suspended, deployTime, null, List.of());
    }

    private OffsetDateTime deployTime(String deploymentId) {
        Deployment deployment = repositoryService.createDeploymentQuery().deploymentId(deploymentId).singleResult();
        return deployment == null ? null : offset(deployment.getDeploymentTime());
    }

    private static OffsetDateTime offset(Date date) {
        return date == null ? null : date.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private static String required(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, name + " is required");
        }
        return value.trim();
    }
}
