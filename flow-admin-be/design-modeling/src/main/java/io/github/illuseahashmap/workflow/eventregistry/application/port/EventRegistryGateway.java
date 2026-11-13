package io.github.illuseahashmap.workflow.eventregistry.application.port;

import io.github.illuseahashmap.workflow.eventregistry.domain.EventField;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventModelDefinition;
import io.github.illuseahashmap.workflow.eventregistry.domain.RestChannelDefinition;
import java.util.List;
import java.util.Optional;

/**
 * What is deployed in the event registry of Flow Engine, reached through its Deployment API. Flow Engine is the
 * source of truth for published models.
 */
public interface EventRegistryGateway {

    /** The version Flow Engine runs after the deployment. Flowable ids stay inside Flow Engine. */
    record Deployed(String definitionId, String deploymentId, int version) {
    }

    Deployed deployEvent(String tenantId, EventModelDefinition model);

    Deployed deployChannel(String tenantId, RestChannelDefinition channel);

    /** Fields of the latest deployed event model of the tenant, empty when none is deployed. */
    Optional<List<EventField>> publishedEventFields(String tenantId, String key);

    boolean channelDeployed(String tenantId, String key);

    /** Flow Engine refuses while a process waits on the event or the latest version of a process uses it. */
    void deleteEvent(String tenantId, String key);

    void deleteChannel(String tenantId, String key);
}
