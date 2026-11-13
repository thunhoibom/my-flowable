package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.eventregistry.application.port.EventReferenceRepository;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventReference;
import io.github.illuseahashmap.workflow.process.application.AgentBindingDeploymentValidator;
import io.github.illuseahashmap.workflow.process.application.port.AgentTaskBindingParser;
import io.github.illuseahashmap.workflow.process.application.port.CallReferenceRepository;
import io.github.illuseahashmap.workflow.process.application.port.ProcessReleaseRepository;
import io.github.illuseahashmap.workflow.process.application.port.ServiceDefinitionSource;
import io.github.illuseahashmap.workflow.process.domain.CallReference;
import io.github.illuseahashmap.workflow.process.domain.ProcessRelease;
import io.github.illuseahashmap.workflow.process.domain.ServiceTaskBinding;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.subprocess.CallActivityDeploymentGuard;
import io.github.illuseahashmap.workflow.process.domain.ReleaseAction;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
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

    private final RepositoryService repositoryService;
    private final ProcessReleaseRepository releases;
    private final BpmnDraftInspector inspector;
    private final ScriptTaskDeploymentGuard scriptTaskDeploymentGuard;
    private final SubProcessDeploymentGuard subProcessDeploymentGuard;
    private final CallActivityDeploymentGuard callActivityDeploymentGuard;
    private final EventRegistryDeploymentGuard eventRegistryDeploymentGuard;
    private final CallReferenceRepository callReferences;
    private final EventReferenceRepository eventReferences;
    private final AgentTaskBindingParser agentTaskBindingParser;
    private final AgentBindingDeploymentValidator agentBindingDeploymentValidator;
    private final CurrentPrincipalProvider principalProvider;
    private final XmlServiceTaskBindingParser serviceTaskBindingParser;
    private final ServiceDefinitionSource serviceDefinitions;

    public ProcessDeployer(RepositoryService repositoryService, ProcessReleaseRepository releases,
                           BpmnDraftInspector inspector, ScriptTaskDeploymentGuard scriptTaskDeploymentGuard,
                           SubProcessDeploymentGuard subProcessDeploymentGuard,
                           CallActivityDeploymentGuard callActivityDeploymentGuard,
                           EventRegistryDeploymentGuard eventRegistryDeploymentGuard,
                           CallReferenceRepository callReferences,
                           EventReferenceRepository eventReferences,
                           AgentTaskBindingParser agentTaskBindingParser,
                           AgentBindingDeploymentValidator agentBindingDeploymentValidator,
                           CurrentPrincipalProvider principalProvider,
                           XmlServiceTaskBindingParser serviceTaskBindingParser,
                           ServiceDefinitionSource serviceDefinitions) {
        this.eventReferences = eventReferences;
        this.repositoryService = repositoryService;
        this.releases = releases;
        this.inspector = inspector;
        this.scriptTaskDeploymentGuard = scriptTaskDeploymentGuard;
        this.subProcessDeploymentGuard = subProcessDeploymentGuard;
        this.callActivityDeploymentGuard = callActivityDeploymentGuard;
        this.eventRegistryDeploymentGuard = eventRegistryDeploymentGuard;
        this.callReferences = callReferences;
        this.agentTaskBindingParser = agentTaskBindingParser;
        this.agentBindingDeploymentValidator = agentBindingDeploymentValidator;
        this.principalProvider = principalProvider;
        this.serviceTaskBindingParser = serviceTaskBindingParser;
        this.serviceDefinitions = serviceDefinitions;
    }

    /** Every guard, then the deployment, then the call and event references of the new version. */
    public ProcessDefinition deployChecked(TenantContext.TenantInfo tenant, String key, String name, String xml) {
        return deployChecked(tenant, key, name, xml, Map.of());
    }

    /**
     * Same as {@link #deployChecked(TenantContext.TenantInfo, String, String, String)}, and every service the BPMN
     * calls goes into the same deployment as a {@code <serviceKey>.service} resource, like a Flowable app carries
     * its service definitions. {@code pinnedServices} wins over the latest published version, so a rollback
     * keeps the services of the version it restores.
     */
    public ProcessDefinition deployChecked(TenantContext.TenantInfo tenant, String key, String name, String xml,
                                           Map<String, byte[]> pinnedServices) {
        inspector.check(key, xml);
        scriptTaskDeploymentGuard.check(xml);
        subProcessDeploymentGuard.check(xml);
        List<EventWaitBinding> eventWaits = eventRegistryDeploymentGuard.check(tenant, xml);
        List<CallActivityDeploymentGuard.Call> calls = callActivityDeploymentGuard.check(tenant, key, xml);
        agentBindingDeploymentValidator.validate(tenant.tenantCode(), agentTaskBindingParser.parse(xml));
        Map<String, byte[]> services = services(xml, pinnedServices);
        ProcessDefinition definition = deploy(tenant, key, name, xml, services);
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
            if (resourceName.endsWith(ServiceDefinitionSource.RESOURCE_SUFFIX)) {
                String serviceKey = resourceName.substring(
                        0, resourceName.length() - ServiceDefinitionSource.RESOURCE_SUFFIX.length());
                try (InputStream content = repositoryService.getResourceAsStream(deploymentId, resourceName)) {
                    services.put(serviceKey, content.readAllBytes());
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }
        }
        return services;
    }

    /**
     * True when deploying {@code xml} now would carry exactly the services already in {@code deploymentId}.
     * A process whose BPMN is unchanged still has something to publish when one of its services was republished.
     */
    public boolean sameServices(String deploymentId, String xml) {
        Map<String, byte[]> current = serviceResources(deploymentId);
        Map<String, byte[]> next = services(xml, Map.of());
        return current.keySet().equals(next.keySet())
                && next.entrySet().stream().allMatch(entry -> Arrays.equals(entry.getValue(), current.get(entry.getKey())));
    }

    private Map<String, byte[]> services(String xml, Map<String, byte[]> pinnedServices) {
        Set<String> keys;
        try {
            keys = serviceTaskBindingParser.parse(xml).stream()
                    .map(ServiceTaskBinding::serviceKey)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, exception.getMessage());
        }
        if (keys.isEmpty()) {
            return Map.of();
        }
        Map<String, byte[]> services = new TreeMap<>();
        for (String serviceKey : keys) {
            byte[] pinned = pinnedServices.get(serviceKey);
            if (pinned != null) {
                services.put(serviceKey, pinned);
            }
        }
        Set<String> unpinned = new TreeSet<>(keys);
        unpinned.removeAll(services.keySet());
        if (!unpinned.isEmpty()) {
            serviceDefinitions.latestPublished(unpinned).forEach((serviceKey, content) -> {
                if (unpinned.contains(serviceKey)) {
                    services.put(serviceKey, content);
                }
            });
        }
        Set<String> missing = new TreeSet<>(keys);
        missing.removeAll(services.keySet());
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Các dịch vụ chưa được publish: " + String.join(", ", missing));
        }
        return services;
    }

    private ProcessDefinition deploy(TenantContext.TenantInfo tenant, String key, String name, String xml,
                                     Map<String, byte[]> services) {
        DeploymentBuilder builder = repositoryService.createDeployment()
                .name(StringUtils.hasText(name) ? name : key)
                .key(key)
                .tenantId(tenant.tenantId())
                .addString(key + ".bpmn20.xml", xml);
        services.forEach((serviceKey, content) ->
                builder.addBytes(ServiceDefinitionSource.resourceName(serviceKey), content));
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

    public ProcessRelease record(TenantContext.TenantInfo tenant, ProcessDefinition definition,
                                 ReleaseAction action, Integer sourceVersion, String hash) {
        CurrentPrincipal principal = principalProvider.current();
        String actor = StringUtils.hasText(principal.username()) ? principal.username() : principal.principalId();
        ProcessRelease release = new ProcessRelease(null, tenant.tenantId(), definition.getKey(),
                definition.getId(), definition.getVersion(), action, sourceVersion, hash, actor,
                OffsetDateTime.now(ZoneOffset.UTC));
        releases.append(release);
        return release;
    }
}
