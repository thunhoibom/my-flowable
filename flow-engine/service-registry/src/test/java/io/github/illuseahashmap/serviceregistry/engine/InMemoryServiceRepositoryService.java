package io.github.illuseahashmap.serviceregistry.engine;

import io.github.illuseahashmap.serviceregistry.domain.PublishedService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Model lookup by parent deployment, for tests that do not need the tables. No fallback to the latest version. */
public final class InMemoryServiceRepositoryService implements ServiceRepositoryService {

    private final Map<String, PublishedService> byParentAndKey = new HashMap<>();

    /** Stores the design-only copy, as a deployment would. */
    public void deploy(String parentDeploymentId, PublishedService service) {
        byParentAndKey.put(parentDeploymentId + "/" + service.serviceKey(), ServiceModelJson.designOnly(service));
    }

    @Override
    public PublishedService getServiceModelByKeyAndParentDeploymentId(
            String key, String parentDeploymentId, String tenantId) {
        PublishedService service = byParentAndKey.get(parentDeploymentId + "/" + key);
        if (service == null) {
            throw new ServiceDefinitionNotFoundException("No service " + key + " in " + parentDeploymentId);
        }
        return service;
    }

    @Override
    public PublishedService getServiceModelByKey(String key, String tenantId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ServiceDeploymentBuilder createDeployment() {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<ServiceDeployment> findDeploymentsByParentDeploymentId(String parentDeploymentId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<ServiceDeployment> findDeployment(String deploymentId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<ServiceDefinition> findDefinitionsByDeploymentId(String deploymentId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<ServiceDefinition> findLatestDefinition(String key, String tenantId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] getResource(String deploymentId, String resourceName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteDeployment(String deploymentId) {
        throw new UnsupportedOperationException();
    }
}
