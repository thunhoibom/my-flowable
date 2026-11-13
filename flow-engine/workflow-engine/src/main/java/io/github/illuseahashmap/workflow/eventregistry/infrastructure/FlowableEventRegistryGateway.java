package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import io.github.illuseahashmap.workflow.eventregistry.application.port.EventRegistryGateway;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventField;
import io.github.illuseahashmap.workflow.eventregistry.domain.RegistryModelType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.flowable.engine.RuntimeService;
import org.flowable.eventregistry.api.ChannelDefinition;
import org.flowable.eventregistry.api.EventDefinition;
import org.flowable.eventregistry.api.EventDeployment;
import org.flowable.eventregistry.api.EventRepositoryService;
import org.springframework.stereotype.Component;

@Component
public class FlowableEventRegistryGateway implements EventRegistryGateway {

    private final EventRepositoryService eventRepositoryService;
    private final RuntimeService runtimeService;

    public FlowableEventRegistryGateway(EventRepositoryService eventRepositoryService, RuntimeService runtimeService) {
        this.eventRepositoryService = eventRepositoryService;
        this.runtimeService = runtimeService;
    }

    @Override
    public Deployed deployEvent(String tenantId, String key, String json) {
        EventDeployment deployment = eventRepositoryService.createDeployment()
                .name(RegistryModelType.EVENT.deploymentName(key)).tenantId(tenantId)
                .addEventDefinition(key + ".event", json).deploy();
        EventDefinition definition = eventRepositoryService.createEventDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult();
        return new Deployed(definition.getId(), deployment.getId(), definition.getVersion());
    }

    @Override
    public Deployed deployChannel(String tenantId, String key, String json) {
        EventDeployment deployment = eventRepositoryService.createDeployment()
                .name(RegistryModelType.CHANNEL.deploymentName(key)).tenantId(tenantId)
                .addChannelDefinition(key + ".channel", json).deploy();
        ChannelDefinition definition = eventRepositoryService.createChannelDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult();
        return new Deployed(definition.getId(), deployment.getId(), definition.getVersion());
    }

    @Override
    public Optional<List<EventField>> publishedEventFields(String tenantId, String key) {
        boolean exists = eventRepositoryService.createEventDefinitionQuery()
                .eventDefinitionKey(key).tenantId(tenantId).count() > 0;
        return exists
                ? Optional.of(FlowableEventRegistryJson.fields(eventRepositoryService.getEventModelByKey(key, tenantId)))
                : Optional.empty();
    }

    @Override
    public boolean channelDeployed(String tenantId, String key) {
        return eventRepositoryService.createChannelDefinitionQuery()
                .channelDefinitionKey(key).tenantId(tenantId).count() > 0;
    }

    @Override
    public Optional<Latest> latest(String tenantId, RegistryModelType type, String key) {
        String deploymentId;
        String resourceName;
        int version;
        if (type == RegistryModelType.EVENT) {
            EventDefinition definition = eventRepositoryService.createEventDefinitionQuery()
                    .eventDefinitionKey(key).tenantId(tenantId).latestVersion().singleResult();
            if (definition == null) {
                return Optional.empty();
            }
            deploymentId = definition.getDeploymentId();
            resourceName = definition.getResourceName();
            version = definition.getVersion();
        } else {
            ChannelDefinition definition = eventRepositoryService.createChannelDefinitionQuery()
                    .channelDefinitionKey(key).tenantId(tenantId).latestVersion().singleResult();
            if (definition == null) {
                return Optional.empty();
            }
            deploymentId = definition.getDeploymentId();
            resourceName = definition.getResourceName();
            version = definition.getVersion();
        }
        try (InputStream content = eventRepositoryService.getResourceAsStream(deploymentId, resourceName)) {
            return Optional.of(new Latest(new String(content.readAllBytes(), StandardCharsets.UTF_8), version));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Override
    public void deleteDeployments(String tenantId, RegistryModelType type, String key) {
        eventRepositoryService.createDeploymentQuery()
                .deploymentName(type.deploymentName(key)).deploymentTenantId(tenantId).list()
                .forEach(deployment -> eventRepositoryService.deleteDeployment(deployment.getId()));
    }

    @Override
    public long waitingSubscriptions(String tenantId, String eventKey) {
        return runtimeService.createEventSubscriptionQuery().eventType(eventKey).tenantId(tenantId).count();
    }
}
