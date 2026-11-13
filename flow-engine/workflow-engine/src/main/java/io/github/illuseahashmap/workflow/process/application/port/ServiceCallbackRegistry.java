package io.github.illuseahashmap.workflow.process.application.port;

import java.util.Map;

/** Parks one service task until a webhook with the same correlation id arrives. */
public interface ServiceCallbackRegistry {

    /** {@code scope} is kept so the callback is classified with the same service definition as the call. */
    void register(
            String correlationId,
            String executionId,
            ServiceScope scope,
            String serviceKey,
            String operationKey,
            Map<String, String> outputVariables,
            long waitTimeoutMillis);
}
