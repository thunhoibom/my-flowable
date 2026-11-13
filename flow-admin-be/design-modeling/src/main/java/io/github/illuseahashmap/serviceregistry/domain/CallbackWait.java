package io.github.illuseahashmap.serviceregistry.domain;

/**
 * A service task paused until POST /service-callbacks/{correlationId} or the deadline.
 * {@code tenantId} and {@code parentDeploymentId} name the service definition the call was made with, so the
 * callback is classified by the same definition.
 */
public record CallbackWait(
        String correlationId,
        String executionId,
        String serviceKey,
        String operationKey,
        String outputMappingJson,
        long deadlineEpochMs,
        String tenantId,
        String parentDeploymentId) {
}
