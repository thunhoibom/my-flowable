package io.github.illuseahashmap.serviceregistry.engine;

import java.time.Instant;

/** One row of {@code flw_se_deployment}. A child of the process deployment named by {@code parentDeploymentId}. */
public record ServiceDeployment(
        String id,
        String name,
        String key,
        String category,
        Instant deployTime,
        String tenantId,
        String parentDeploymentId) {
}
