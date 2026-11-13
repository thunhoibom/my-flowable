package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.illuseahashmap.workflow.config.EventRegistryProperties;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventFieldView;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventModelView;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.SaveEventModelRequest;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.SaveRestChannelRequest;
import io.github.illuseahashmap.workflow.eventregistry.application.port.EventRegistryGateway;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventField;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventModelDefinition;
import io.github.illuseahashmap.workflow.eventregistry.domain.RestChannelDefinition;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Event and channel drafts in Flow Admin BE, published through a fake Deployment API. */
class EventModelServiceImplTest {

    private static final EventFieldView ORDER_ID = new EventFieldView("orderId", "string", true, false, false);
    private static final EventFieldView AMOUNT = new EventFieldView("amount", "double", false, false, false);

    private final FakeGateway gateway = new FakeGateway();
    private EventModelServiceImpl events;
    private RestChannelServiceImpl channels;

    @BeforeEach
    void setUp() {
        TenantProvider tenant = () -> new TenantContext.TenantInfo("t1", "t1", "Tenant 1");
        CurrentPrincipalProvider principal =
                () -> new CurrentPrincipal("USER", "u1", "designer", "Designer", "t1", Set.of(), Set.of());
        var repositories = new InMemoryEventRegistryRepositories();
        var properties = new EventRegistryProperties();
        properties.setEnabled(true);
        var audit = new WorkflowOperationAuditService(principal, tenant);
        events = new EventModelServiceImpl(repositories.drafts, repositories.releases, gateway, audit, tenant,
                principal, properties);
        channels = new RestChannelServiceImpl(repositories.drafts, repositories.releases, gateway, audit, tenant,
                principal, properties);
    }

    private EventModelView save(String key, long revision, EventFieldView... fields) {
        return events.saveDraft(key, new SaveEventModelRequest(revision, "Đã thanh toán", null, List.of(fields)));
    }

    @Test
    void publishesThroughTheEngineOnceAndSkipsUnchangedContent() {
        EventModelView draft = save("paid", 0, ORDER_ID, AMOUNT);

        EventModelView published = events.publish("paid", draft.revision());
        events.publish("paid", draft.revision());

        assertThat(gateway.deployedEvents).containsExactly("paid");
        assertThat(published.publishedVersion()).isEqualTo(1);
        assertThat(published.publishedFields()).containsExactly(ORDER_ID, AMOUNT);
        assertThat(published.changed()).isFalse();
    }

    @Test
    void refusesAnIncompatibleChangeOfAPublishedEvent() {
        events.publish("paid2", save("paid2", 0, ORDER_ID, AMOUNT).revision());
        EventModelView changed = save("paid2", 1, ORDER_ID);

        assertThatThrownBy(() -> events.publish("paid2", changed.revision()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("không tương thích");
    }

    @Test
    void channelsNeedPublishedEventsAndDeletionAsksTheEngine() {
        events.publish("ship", save("ship", 0, ORDER_ID).revision());
        long revision = channels.saveDraft("shipHook", new SaveRestChannelRequest(0, "Hook", null, "FIXED", "ship",
                null, List.of("erp"), List.of())).revision();
        channels.publish("shipHook", revision);
        assertThat(gateway.deployedChannels).containsExactly("shipHook");

        assertThatThrownBy(() -> events.delete("ship")).hasMessageContaining("shipHook");
        channels.delete("shipHook");
        events.delete("ship");

        assertThat(gateway.deletedChannels).containsExactly("shipHook");
        assertThat(gateway.deletedEvents).containsExactly("ship");
    }

    /** Deployment API of Flow Engine, in memory. */
    private static final class FakeGateway implements EventRegistryGateway {

        final List<String> deployedEvents = new ArrayList<>();
        final List<String> deployedChannels = new ArrayList<>();
        final List<String> deletedEvents = new ArrayList<>();
        final List<String> deletedChannels = new ArrayList<>();
        private final Map<String, List<EventField>> events = new HashMap<>();
        private final Map<String, Integer> versions = new HashMap<>();

        @Override
        public Deployed deployEvent(String tenantId, EventModelDefinition model) {
            deployedEvents.add(model.key());
            events.put(model.key(), model.fields());
            return new Deployed(null, null, versions.merge(model.key(), 1, Integer::sum));
        }

        @Override
        public Deployed deployChannel(String tenantId, RestChannelDefinition channel) {
            deployedChannels.add(channel.key());
            return new Deployed(null, null, versions.merge("channel:" + channel.key(), 1, Integer::sum));
        }

        @Override
        public Optional<List<EventField>> publishedEventFields(String tenantId, String key) {
            return Optional.ofNullable(events.get(key));
        }

        @Override
        public boolean channelDeployed(String tenantId, String key) {
            return deployedChannels.contains(key) && !deletedChannels.contains(key);
        }

        @Override
        public void deleteEvent(String tenantId, String key) {
            deletedEvents.add(key);
            events.remove(key);
        }

        @Override
        public void deleteChannel(String tenantId, String key) {
            deletedChannels.add(key);
        }
    }
}
