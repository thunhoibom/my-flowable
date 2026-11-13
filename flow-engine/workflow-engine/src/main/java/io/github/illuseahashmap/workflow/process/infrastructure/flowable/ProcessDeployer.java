package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.eventregistry.application.port.EventReferenceRepository;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventReference;
import io.github.illuseahashmap.workflow.process.application.AgentBindingDeploymentValidator;
import io.github.illuseahashmap.workflow.process.application.port.AgentTaskBindingParser;
import io.github.illuseahashmap.workflow.process.application.port.CallReferenceRepository;
import io.github.illuseahashmap.workflow.process.domain.CallReference;
import io.github.illuseahashmap.workflow.process.domain.ServiceTaskBinding;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.subprocess.CallActivityDeploymentGuard;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.DeploymentBuilder;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** The only place that calls createDeployment. Callers own the transaction. */
@Component
public class ProcessDeployer {

    /** Resource suffix of a service definition in a deployment, same as Flowable. */
    public static final String RESOURCE_SUFFIX = ".service";

    private final RepositoryService repositoryService;
    private final BpmnDraftInspector inspector;
    private final ScriptTaskDeploymentGuard scriptTaskDeploymentGuard;
    private final SubProcessDeploymentGuard subProcessDeploymentGuard;
    private final CallActivityDeploymentGuard callActivityDeploymentGuard;
    private final EventRegistryDeploymentGuard eventRegistryDeploymentGuard;
    private final CallReferenceRepository callReferences;
    private final EventReferenceRepository eventReferences;
    private final AgentTaskBindingParser agentTaskBindingParser;
    private final AgentBindingDeploymentValidator agentBindingDeploymentValidator;
    private final XmlServiceTaskBindingParser serviceTaskBindingParser;

    public ProcessDeployer(RepositoryService repositoryService, BpmnDraftInspector inspector,
                           ScriptTaskDeploymentGuard scriptTaskDeploymentGuard,
                           SubProcessDeploymentGuard subProcessDeploymentGuard,
                           CallActivityDeploymentGuard callActivityDeploymentGuard,
                           EventRegistryDeploymentGuard eventRegistryDeploymentGuard,
                           CallReferenceRepository callReferences,
                           EventReferenceRepository eventReferences,
                           AgentTaskBindingParser agentTaskBindingParser,
                           AgentBindingDeploymentValidator agentBindingDeploymentValidator,
                           XmlServiceTaskBindingParser serviceTaskBindingParser) {
        this.eventReferences = eventReferences;
        this.repositoryService = repositoryService;
        this.inspector = inspector;
        this.scriptTaskDeploymentGuard = scriptTaskDeploymentGuard;
        this.subProcessDeploymentGuard = subProcessDeploymentGuard;
        this.callActivityDeploymentGuard = callActivityDeploymentGuard;
        this.eventRegistryDeploymentGuard = eventRegistryDeploymentGuard;
        this.callReferences = callReferences;
        this.agentTaskBindingParser = agentTaskBindingParser;
        this.agentBindingDeploymentValidator = agentBindingDeploymentValidator;
        this.serviceTaskBindingParser = serviceTaskBindingParser;
    }

    /**
     * Every guard, then the deployment, then the call and event references of the new version.
     * Every service the BPMN calls goes into the same deployment as a {@code <serviceKey>.service} resource, like a
     * Flowable app carries its service definitions. {@code services} must hold each of them; nothing is looked up
     * elsewhere. Services the BPMN does not call are left out. The Flowable deployment key is {@code deploymentKey}
     * (the publish id), which makes a deployment findable again when the same publish is sent twice.
     */
    public ProcessDefinition deployChecked(TenantContext.TenantInfo tenant, String deploymentKey, String key,
                                           String name, String xml, Map<String, byte[]> services) {
        inspector.check(key, xml);
        scriptTaskDeploymentGuard.check(xml);
        subProcessDeploymentGuard.check(xml);
        List<EventWaitBinding> eventWaits = eventRegistryDeploymentGuard.check(tenant, xml);
        List<CallActivityDeploymentGuard.Call> calls = callActivityDeploymentGuard.check(tenant, key, xml);
        agentBindingDeploymentValidator.validate(tenant.tenantCode(), agentTaskBindingParser.parse(xml));
        Map<String, byte[]> called = calledServices(xml, services);
        ProcessDefinition definition = deploy(tenant, deploymentKey, key, name, xml, called);
        callReferences.replaceForDefinition(tenant.tenantId(), definition.getId(), calls.stream()
                .map(call -> new CallReference(definition.getId(), key, definition.getVersion(),
                        call.activityId(), call.calledKey()))
                .toList());
        eventReferences.replaceForDefinition(tenant.tenantId(), definition.getId(), eventWaits.stream()
                .map(wait -> new EventReference(definition.getId(), wait.activityId(), wait.eventKey()))
                .toList());
        return definition;
    }

    /** The {@code .service} resources of a deployment, by service key. Empty for deployments made before them. */
    public Map<String, byte[]> serviceResources(String deploymentId) {
        Map<String, byte[]> services = new TreeMap<>();
        for (String resourceName : repositoryService.getDeploymentResourceNames(deploymentId)) {
            if (resourceName.endsWith(RESOURCE_SUFFIX)) {
                String serviceKey = resourceName.substring(
                        0, resourceName.length() - RESOURCE_SUFFIX.length());
                try (InputStream content = repositoryService.getResourceAsStream(deploymentId, resourceName)) {
                    services.put(serviceKey, content.readAllBytes());
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }
        }
        return services;
    }

    /** Keys of the services the BPMN calls, sorted. */
    public Set<String> serviceKeys(String xml) {
        try {
            return serviceTaskBindingParser.parse(xml).stream()
                    .map(ServiceTaskBinding::serviceKey)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, exception.getMessage());
        }
    }

    private Map<String, byte[]> calledServices(String xml, Map<String, byte[]> services) {
        Map<String, byte[]> called = new TreeMap<>();
        Set<String> missing = new TreeSet<>();
        for (String serviceKey : serviceKeys(xml)) {
            byte[] content = services.get(serviceKey);
            if (content == null) {
                missing.add(serviceKey);
            } else {
                called.put(serviceKey, content);
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Thiếu định nghĩa của các dịch vụ: " + String.join(", ", missing));
        }
        return called;
    }

    private ProcessDefinition deploy(TenantContext.TenantInfo tenant, String deploymentKey, String key, String name,
                                     String xml, Map<String, byte[]> services) {
        DeploymentBuilder builder = repositoryService.createDeployment()
                .name(StringUtils.hasText(name) ? name : key)
                .key(deploymentKey)
                .tenantId(tenant.tenantId())
                .addString(key + ".bpmn20.xml", xml);
        services.forEach((serviceKey, content) ->
                builder.addBytes(serviceKey + RESOURCE_SUFFIX, content));
        Deployment deployment = builder.deploy();
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId())
                .processDefinitionTenantId(tenant.tenantId())
                .singleResult();
        if (definition == null) {
            throw new BusinessException(ErrorCode.WORKFLOW_ERROR, "No process definition was deployed");
        }
        if (!key.equals(definition.getKey())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "BPMN process id must equal processDefinitionKey");
        }
        return definition;
    }
}
