package io.github.illuseahashmap.workflow.process.application.port;

import java.util.Map;
import java.util.Optional;

public interface ServiceRegistryGateway {

    /** The operation contract of the service definition found in {@code scope}. */
    Optional<PublishedCallContract> findPublished(ServiceScope scope, String serviceKey, String operationKey);

    Map<String, Object> invoke(ServiceScope scope, String serviceKey, String operationKey,
                               Map<String, Object> resolvedInputs);
}
