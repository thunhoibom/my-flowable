package io.github.illuseahashmap.workflow.serviceregistry;

import io.github.illuseahashmap.serviceregistry.engine.ServiceDeployment;
import io.github.illuseahashmap.serviceregistry.engine.ServiceDeploymentBuilder;
import io.github.illuseahashmap.serviceregistry.engine.ServiceRepositoryService;
import java.util.Map;
import org.flowable.common.engine.api.repository.EngineDeployment;
import org.flowable.common.engine.api.repository.EngineResource;
import org.flowable.common.engine.impl.EngineDeployer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Same role as Flowable's EventDeployer: {@code .service} resources of a process deployment become one child
 * service deployment whose parent is the process deployment. Deleting the process deployment deletes it.
 * Runs inside Flowable's deploy command, so it commits or rolls back with the BPMN.
 */
public class ServiceDeployer implements EngineDeployer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceDeployer.class);

    private final ServiceRepositoryService repository;

    public ServiceDeployer(ServiceRepositoryService repository) {
        this.repository = repository;
    }

    @Override
    public void deploy(EngineDeployment deployment, Map<String, Object> deploymentSettings) {
        if (!deployment.isNew()) {
            return;
        }
        ServiceDeploymentBuilder builder = null;
        for (Map.Entry<String, EngineResource> resource : deployment.getResources().entrySet()) {
            if (resource.getKey().endsWith(ServiceRepositoryService.RESOURCE_SUFFIX)) {
                LOGGER.debug("ServiceDeployer: processing resource {}", resource.getKey());
                if (builder == null) {
                    builder = repository.createDeployment().name(deployment.getName());
                }
                builder.addServiceDefinitionBytes(resource.getKey(), resource.getValue().getBytes());
            }
        }
        if (builder != null) {
            builder.parentDeploymentId(deployment.getId());
            if (deployment.getTenantId() != null && !deployment.getTenantId().isEmpty()) {
                builder.tenantId(deployment.getTenantId());
            }
            builder.deploy();
        }
    }

    @Override
    public void undeploy(EngineDeployment parentDeployment, boolean cascade) {
        for (ServiceDeployment child : repository.findDeploymentsByParentDeploymentId(parentDeployment.getId())) {
            repository.deleteDeployment(child.id());
        }
    }
}
