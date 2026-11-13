package io.github.illuseahashmap.workflow.eventregistry.application.port;

import io.github.illuseahashmap.workflow.eventregistry.domain.EventField;
import io.github.illuseahashmap.workflow.eventregistry.domain.RegistryModelType;
import java.util.List;
import java.util.Optional;

/** What is deployed in Flowable's event registry. Flowable is the source of truth for published models. */
public interface EventRegistryGateway {

    record Deployed(String definitionId, String deploymentId, int version) {
    }

    /** The JSON of the latest deployed version of a model, as Flowable stores it. */
    record Latest(String json, int version) {
    }

    Deployed deployEvent(String tenantId, String key, String json);

    Deployed deployChannel(String tenantId, String key, String json);

    /** Fields of the latest deployed event model of the tenant, empty when none is deployed. */
    Optional<List<EventField>> publishedEventFields(String tenantId, String key);

    boolean channelDeployed(String tenantId, String key);

    /** The latest deployed event or channel model of the tenant, empty when none is deployed. */
    Optional<Latest> latest(String tenantId, RegistryModelType type, String key);

    void deleteDeployments(String tenantId, RegistryModelType type, String key);

    /** Process executions of the tenant currently subscribed to {@code eventKey}. */
    long waitingSubscriptions(String tenantId, String eventKey);
}
