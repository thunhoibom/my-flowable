package io.github.illuseahashmap.serviceregistry.engine;

/** One row of {@code flw_se_definition}: a version of a service key, deployed in one service deployment. */
public record ServiceDefinition(
        String id,
        String key,
        int version,
        String deploymentId,
        String tenantId,
        String name,
        String resourceName) {
}
