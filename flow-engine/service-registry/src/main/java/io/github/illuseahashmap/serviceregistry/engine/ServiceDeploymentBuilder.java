package io.github.illuseahashmap.serviceregistry.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Same shape as the Flowable deployment builders. Resources ending in {@code .service} become definitions. */
public final class ServiceDeploymentBuilder {

    private final JdbcServiceRepositoryService repository;
    private final Map<String, byte[]> resources = new LinkedHashMap<>();
    private String name;
    private String key;
    private String category;
    private String tenantId = ServiceRepositoryService.NO_TENANT_ID;
    private String parentDeploymentId;

    ServiceDeploymentBuilder(JdbcServiceRepositoryService repository) {
        this.repository = repository;
    }

    public ServiceDeploymentBuilder name(String name) {
        this.name = name;
        return this;
    }

    public ServiceDeploymentBuilder key(String key) {
        this.key = key;
        return this;
    }

    public ServiceDeploymentBuilder category(String category) {
        this.category = category;
        return this;
    }

    public ServiceDeploymentBuilder tenantId(String tenantId) {
        this.tenantId = tenantId == null ? ServiceRepositoryService.NO_TENANT_ID : tenantId;
        return this;
    }

    public ServiceDeploymentBuilder parentDeploymentId(String parentDeploymentId) {
        this.parentDeploymentId = parentDeploymentId;
        return this;
    }

    public ServiceDeploymentBuilder addServiceDefinitionBytes(String resourceName, byte[] bytes) {
        if (resourceName == null || !resourceName.endsWith(ServiceRepositoryService.RESOURCE_SUFFIX)) {
            throw new IllegalArgumentException("Service resource name must end with "
                    + ServiceRepositoryService.RESOURCE_SUFFIX + ": " + resourceName);
        }
        resources.put(resourceName, bytes.clone());
        return this;
    }

    public ServiceDeployment deploy() {
        return repository.deploy(this);
    }

    String name() {
        return name;
    }

    String key() {
        return key;
    }

    String category() {
        return category;
    }

    String tenantId() {
        return tenantId;
    }

    String parentDeploymentId() {
        return parentDeploymentId;
    }

    Map<String, byte[]> resources() {
        return Collections.unmodifiableMap(resources);
    }
}
