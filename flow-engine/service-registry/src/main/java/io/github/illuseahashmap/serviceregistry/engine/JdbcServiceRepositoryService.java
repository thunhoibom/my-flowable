package io.github.illuseahashmap.serviceregistry.engine;

import io.github.illuseahashmap.serviceregistry.domain.PublishedService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Service engine on the {@code flw_se_*} tables. Every write joins the caller's transaction, so a service
 * deployment made while Flowable deploys a process rolls back with it.
 */
public final class JdbcServiceRepositoryService implements ServiceRepositoryService {

    private final JdbcTemplate jdbc;
    private final ServiceModelJson json;
    private final ServiceDefinitionCache cache;
    private final boolean alwaysLookupLatestDefinitionVersion;
    private final String defaultTenantId;

    /**
     * @param cacheLimit                          like {@code flowable.service-registry.definition-cache-limit}; 0 or less means no limit
     * @param alwaysLookupLatestDefinitionVersion like Flowable's flag of the same name: ignore the parent deployment
     * @param defaultTenantId                     tenant to fall back to when nothing is found; null turns the fallback off
     */
    public JdbcServiceRepositoryService(DataSource dataSource, int cacheLimit,
                                        boolean alwaysLookupLatestDefinitionVersion, String defaultTenantId) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.json = new ServiceModelJson();
        this.cache = new ServiceDefinitionCache(cacheLimit);
        this.alwaysLookupLatestDefinitionVersion = alwaysLookupLatestDefinitionVersion;
        this.defaultTenantId = defaultTenantId;
    }

    @Override
    public ServiceDeploymentBuilder createDeployment() {
        return new ServiceDeploymentBuilder(this);
    }

    /** Same steps as Flowable's deployers: insert the deployment and resources, then version each definition. */
    ServiceDeployment deploy(ServiceDeploymentBuilder builder) {
        ServiceDeployment deployment = new ServiceDeployment(UUID.randomUUID().toString(), builder.name(),
                builder.key(), builder.category(), Instant.now(), builder.tenantId(), builder.parentDeploymentId());
        jdbc.update("""
                INSERT INTO flw_se_deployment
                    (id_, name_, key_, category_, deploy_time_, tenant_id_, parent_deployment_id_)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, deployment.id(), deployment.name(), deployment.key(), deployment.category(),
                Timestamp.from(deployment.deployTime()), deployment.tenantId(), deployment.parentDeploymentId());

        Map<String, String> keysInDeployment = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> resource : builder.resources().entrySet()) {
            jdbc.update("""
                    INSERT INTO flw_se_deployment_resource (id_, name_, deployment_id_, resource_bytes_)
                    VALUES (?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), resource.getKey(), deployment.id(), resource.getValue());

            PublishedService model = json.read(resource.getValue());
            String previous = keysInDeployment.put(model.serviceKey(), resource.getKey());
            if (previous != null) {
                throw new IllegalArgumentException("Service key " + model.serviceKey()
                        + " is used by both " + previous + " and " + resource.getKey());
            }
            int version = findLatestDefinition(model.serviceKey(), deployment.tenantId())
                    .map(latest -> latest.version() + 1)
                    .orElse(1);
            jdbc.update("""
                    INSERT INTO flw_se_definition
                        (id_, rev_, key_, deployment_id_, tenant_id_, name_, version_, resource_name_)
                    VALUES (?, 1, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), model.serviceKey(), deployment.id(), deployment.tenantId(),
                    model.name(), version, resource.getKey());
        }
        return deployment;
    }

    @Override
    public List<ServiceDeployment> findDeploymentsByParentDeploymentId(String parentDeploymentId) {
        return jdbc.query("""
                SELECT * FROM flw_se_deployment WHERE parent_deployment_id_ = ? ORDER BY deploy_time_
                """, JdbcServiceRepositoryService::deployment, parentDeploymentId);
    }

    @Override
    public Optional<ServiceDeployment> findDeployment(String deploymentId) {
        return jdbc.query("SELECT * FROM flw_se_deployment WHERE id_ = ?",
                JdbcServiceRepositoryService::deployment, deploymentId).stream().findFirst();
    }

    @Override
    public List<ServiceDefinition> findDefinitionsByDeploymentId(String deploymentId) {
        return jdbc.query("SELECT * FROM flw_se_definition WHERE deployment_id_ = ? ORDER BY key_",
                JdbcServiceRepositoryService::definition, deploymentId);
    }

    @Override
    public Optional<ServiceDefinition> findLatestDefinition(String key, String tenantId) {
        return jdbc.query("""
                SELECT * FROM flw_se_definition
                WHERE key_ = ? AND tenant_id_ = ?
                ORDER BY version_ DESC
                LIMIT 1
                """, JdbcServiceRepositoryService::definition, key, tenant(tenantId)).stream().findFirst();
    }

    @Override
    public byte[] getResource(String deploymentId, String resourceName) {
        return jdbc.query("""
                SELECT resource_bytes_ FROM flw_se_deployment_resource WHERE deployment_id_ = ? AND name_ = ?
                """, (resultSet, row) -> resultSet.getBytes(1), deploymentId, resourceName)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ServiceDefinitionNotFoundException(
                        "No resource " + resourceName + " in service deployment " + deploymentId));
    }

    @Override
    public void deleteDeployment(String deploymentId) {
        List<ServiceDefinition> definitions = findDefinitionsByDeploymentId(deploymentId);
        jdbc.update("DELETE FROM flw_se_definition WHERE deployment_id_ = ?", deploymentId);
        jdbc.update("DELETE FROM flw_se_deployment_resource WHERE deployment_id_ = ?", deploymentId);
        jdbc.update("DELETE FROM flw_se_deployment WHERE id_ = ?", deploymentId);
        definitions.forEach(definition -> cache.remove(definition.id()));
    }

    @Override
    public PublishedService getServiceModelByKey(String key, String tenantId) {
        return getServiceModelByKeyAndParentDeploymentId(key, null, tenantId);
    }

    @Override
    public PublishedService getServiceModelByKeyAndParentDeploymentId(
            String key, String parentDeploymentId, String tenantId) {
        if (key == null) {
            throw new IllegalArgumentException("Service key is required");
        }
        String tenant = tenant(tenantId);
        ServiceDefinition definition = null;
        if (parentDeploymentId != null && !alwaysLookupLatestDefinitionVersion) {
            definition = findInParentDeployment(key, parentDeploymentId, tenant).orElse(null);
        }
        if (definition == null) {
            definition = findLatestDefinition(key, tenant).orElse(null);
        }
        if (definition == null && defaultTenantId != null && !defaultTenantId.equals(tenant)) {
            definition = findLatestDefinition(key, defaultTenantId).orElse(null);
        }
        if (definition == null) {
            throw new ServiceDefinitionNotFoundException("No service definition found for key '" + key
                    + "' for parent deployment id '" + parentDeploymentId + "' and tenant '" + tenant + "'");
        }
        ServiceDefinition found = definition;
        return cache.get(found.id(), () -> json.read(getResource(found.deploymentId(), found.resourceName())));
    }

    /** First child deployment of the parent, like GetEventModelCmd, then the key inside it. */
    private Optional<ServiceDefinition> findInParentDeployment(String key, String parentDeploymentId, String tenant) {
        List<String> deploymentIds = new ArrayList<>(jdbc.queryForList("""
                SELECT id_ FROM flw_se_deployment
                WHERE parent_deployment_id_ = ? AND tenant_id_ = ?
                ORDER BY deploy_time_
                """, String.class, parentDeploymentId, tenant));
        if (deploymentIds.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT * FROM flw_se_definition WHERE deployment_id_ = ? AND key_ = ? AND tenant_id_ = ?
                """, JdbcServiceRepositoryService::definition, deploymentIds.getFirst(), key, tenant)
                .stream()
                .findFirst();
    }

    private static String tenant(String tenantId) {
        return tenantId == null ? NO_TENANT_ID : tenantId;
    }

    private static ServiceDeployment deployment(ResultSet resultSet, int row) throws SQLException {
        Timestamp deployTime = resultSet.getTimestamp("deploy_time_");
        return new ServiceDeployment(
                resultSet.getString("id_"),
                resultSet.getString("name_"),
                resultSet.getString("key_"),
                resultSet.getString("category_"),
                deployTime == null ? null : deployTime.toInstant(),
                resultSet.getString("tenant_id_"),
                resultSet.getString("parent_deployment_id_"));
    }

    private static ServiceDefinition definition(ResultSet resultSet, int row) throws SQLException {
        return new ServiceDefinition(
                resultSet.getString("id_"),
                resultSet.getString("key_"),
                resultSet.getInt("version_"),
                resultSet.getString("deployment_id_"),
                resultSet.getString("tenant_id_"),
                resultSet.getString("name_"),
                resultSet.getString("resource_name_"));
    }
}
