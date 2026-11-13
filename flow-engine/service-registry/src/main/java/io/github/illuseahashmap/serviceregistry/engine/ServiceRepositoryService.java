package io.github.illuseahashmap.serviceregistry.engine;

import io.github.illuseahashmap.serviceregistry.domain.PublishedService;
import java.util.List;
import java.util.Optional;

/**
 * Service engine, modelled on Flowable's ServiceRegistryRepositoryService and the open source
 * EventRepositoryService: deployments, versioned definitions, and model lookup within a parent deployment.
 */
public interface ServiceRepositoryService {

    /** Flowable's NO_TENANT_ID. */
    String NO_TENANT_ID = "";

    /** Same suffix as Flowable service definitions. */
    String RESOURCE_SUFFIX = ".service";

    ServiceDeploymentBuilder createDeployment();

    List<ServiceDeployment> findDeploymentsByParentDeploymentId(String parentDeploymentId);

    Optional<ServiceDeployment> findDeployment(String deploymentId);

    List<ServiceDefinition> findDefinitionsByDeploymentId(String deploymentId);

    Optional<ServiceDefinition> findLatestDefinition(String key, String tenantId);

    byte[] getResource(String deploymentId, String resourceName);

    /** Deletes the deployment, its resources and definitions, and evicts the definitions from the cache. */
    void deleteDeployment(String deploymentId);

    /** Latest version of the key in the tenant. */
    PublishedService getServiceModelByKey(String key, String tenantId);

    /**
     * The definition deployed under {@code parentDeploymentId}; when there is none, the latest version
     * of the key in the tenant, then in the default tenant when that fallback is on. Same order as
     * Flowable's GetEventModelCmd. A null parent means the latest version.
     *
     * @throws ServiceDefinitionNotFoundException when nothing matches
     */
    PublishedService getServiceModelByKeyAndParentDeploymentId(String key, String parentDeploymentId, String tenantId);
}
