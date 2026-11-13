package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import io.github.illuseahashmap.designhub.engine.FlowEngineClient;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventFieldView;
import io.github.illuseahashmap.workflow.eventregistry.application.port.EventRegistryGateway;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventField;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventModelDefinition;
import io.github.illuseahashmap.workflow.eventregistry.domain.RestChannelDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The event registry of Flow Engine, through {@code /api/deployment/events} and {@code /api/deployment/channels}. */
@Component
public class EngineEventRegistryGateway implements EventRegistryGateway {

    private final FlowEngineClient engine;

    public EngineEventRegistryGateway(FlowEngineClient engine) {
        this.engine = engine;
    }

    @Override
    public Deployed deployEvent(String tenantId, EventModelDefinition model) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("key", model.key());
        event.put("name", model.name());
        event.put("description", model.description());
        event.put("fields", model.fields().stream().map(RegistryDraftJson::view).toList());
        FlowEngineClient.EventDeployment result = engine.deployEvents(request(List.of(event), List.of()));
        return new Deployed(null, null, result.events().getFirst().version());
    }

    @Override
    public Deployed deployChannel(String tenantId, RestChannelDefinition channel) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("key", channel.key());
        value.put("name", channel.name());
        value.put("description", channel.description());
        value.put("detection", channel.detection().name());
        value.put("detectionValue", channel.detectionValue());
        value.put("allowedEventKeys", channel.allowedEventKeys());
        value.put("userIds", channel.userIds());
        value.put("groupIds", channel.groupIds());
        FlowEngineClient.EventDeployment result = engine.deployEvents(request(List.of(), List.of(value)));
        return new Deployed(null, null, result.channels().getFirst().version());
    }

    @Override
    public Optional<List<EventField>> publishedEventFields(String tenantId, String key) {
        return engine.event(key).map(model -> model.fields() == null ? List.<EventField>of()
                : model.fields().stream().map(EngineEventRegistryGateway::field).toList());
    }

    @Override
    public boolean channelDeployed(String tenantId, String key) {
        return engine.channel(key).isPresent();
    }

    @Override
    public void deleteEvent(String tenantId, String key) {
        engine.deleteEvent(key);
    }

    @Override
    public void deleteChannel(String tenantId, String key) {
        engine.deleteChannel(key);
    }

    private static Map<String, Object> request(List<Map<String, Object>> events, List<Map<String, Object>> channels) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("publishId", UUID.randomUUID().toString());
        request.put("events", events);
        request.put("channels", channels);
        return request;
    }

    private static EventField field(EventFieldView view) {
        return RegistryDraftJson.field(view);
    }
}
