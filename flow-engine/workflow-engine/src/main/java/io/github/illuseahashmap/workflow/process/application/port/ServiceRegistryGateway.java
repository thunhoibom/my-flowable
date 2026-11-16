package io.github.illuseahashmap.workflow.process.application.port;

import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

public interface ServiceRegistryGateway {

    /** The operation contract of the service definition found in {@code scope}. */
    Optional<PublishedCallContract> findPublished(ServiceScope scope, String serviceKey, String operationKey);

    /**
     * Calls the operation. {@code expressions} evaluates the expressions of the service model (base URL,
     * credentials) on the running process, e.g. {@code ${tenant.crmBaseUrl}}; it returns text or null.
     */
    Map<String, Object> invoke(ServiceScope scope, String serviceKey, String operationKey,
                               Map<String, Object> resolvedInputs, UnaryOperator<String> expressions);
}
