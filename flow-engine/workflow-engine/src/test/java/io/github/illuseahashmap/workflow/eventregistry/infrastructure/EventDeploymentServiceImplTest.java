package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.illuseahashmap.workflow.config.EventRegistryProperties;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventDeploymentRequest;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventDeploymentResult;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventFieldView;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The receiving side of {@code POST /api/deployment/events}. */
class EventDeploymentServiceImplTest {

    private static final TenantContext.TenantInfo TENANT = new TenantContext.TenantInfo("t-ev", "t-ev", "Tenant");
    private static final EventFieldView ORDER_ID = new EventFieldView("orderId", "string", true, false, false);
    private static final EventFieldView AMOUNT = new EventFieldView("amount", "double", false, false, false);

    private static EventRegistryTestEngine test;
    private EventRegistryProperties properties;
    private EventDeploymentServiceImpl service;

    @BeforeAll
    static void start() {
        test = new EventRegistryTestEngine("event-deployment-service");
    }

    @AfterAll
    static void stop() {
        test.close();
    }

    @BeforeEach
    void setUp() {
        properties = new EventRegistryProperties();
        properties.setEnabled(true);
        service = new EventDeploymentServiceImpl(test.gateway, new InMemoryEventRegistryRepositories().references,
                mock(WorkflowOperationAuditService.class),
                () -> TENANT, properties);
    }

    @Test
    void sameContentIsNotDeployedAgainAndTheLatestVersionWins() {
        EventDeploymentResult first = service.deploy(request("pub-1", List.of(event("paid", ORDER_ID)), List.of()));
        EventDeploymentResult resent = service.deploy(request("pub-1", List.of(event("paid", ORDER_ID)), List.of()));
        EventDeploymentResult changed = service.deploy(
                request("pub-2", List.of(event("paid", ORDER_ID, AMOUNT)), List.of()));

        assertThat(first.events()).containsExactly(new EventDeploymentResult.Item("paid", 1, true));
        assertThat(resent.events()).containsExactly(new EventDeploymentResult.Item("paid", 1, false));
        assertThat(changed.events()).containsExactly(new EventDeploymentResult.Item("paid", 2, true));
        assertThat(test.gateway.publishedEventFields("t-ev", "paid").orElseThrow()).hasSize(2);
    }

    @Test
    void deploysChannelsAfterTheEventsOfTheSameRequest() {
        EventDeploymentResult result = service.deploy(request("pub-3", List.of(event("shipped", ORDER_ID)),
                List.of(channel("shipping", "shipped"))));
        EventDeploymentResult resent = service.deploy(request("pub-3", List.of(event("shipped", ORDER_ID)),
                List.of(channel("shipping", "shipped"))));

        assertThat(result.channels()).containsExactly(new EventDeploymentResult.Item("shipping", 1, true));
        assertThat(resent.channels()).containsExactly(new EventDeploymentResult.Item("shipping", 1, false));
        assertThat(test.gateway.channelDeployed("t-ev", "shipping")).isTrue();
    }

    @Test
    void rejectsAChannelForAnEventThatIsNotDeployed() {
        assertThatThrownBy(() -> service.deploy(request("pub-4", List.of(), List.of(channel("lost", "neverSent")))))
                .hasMessageContaining("neverSent");
        assertThat(test.gateway.channelDeployed("t-ev", "lost")).isFalse();
    }

    @Test
    void rejectsInvalidDuplicateOrForeignRequests() {
        assertThatThrownBy(() -> service.deploy(request("pub-5",
                List.of(event("twice", ORDER_ID), event("twice", ORDER_ID)), List.of())))
                .hasMessageContaining("hai lần");
        assertThatThrownBy(() -> service.deploy(request("pub-6",
                List.of(event("bad", new EventFieldView("x", "nope", false, false, false))), List.of())))
                .hasMessageContaining("bad");
        assertThatThrownBy(() -> service.deploy(new EventDeploymentRequest("pub-7", "someone-else",
                List.of(event("foreign", ORDER_ID)), List.of())))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        properties.setEnabled(false);
        assertThatThrownBy(() -> service.deploy(request("pub-8", List.of(event("off", ORDER_ID)), List.of())))
                .isInstanceOf(BusinessException.class);
    }

    private static EventDeploymentRequest request(String publishId, List<EventDeploymentRequest.Event> events,
                                                  List<EventDeploymentRequest.Channel> channels) {
        return new EventDeploymentRequest(publishId, "t-ev", events, channels);
    }

    private static EventDeploymentRequest.Event event(String key, EventFieldView... fields) {
        return new EventDeploymentRequest.Event(key, "Sự kiện " + key, null, List.of(fields));
    }

    private static EventDeploymentRequest.Channel channel(String key, String eventKey) {
        return new EventDeploymentRequest.Channel(key, "Kênh " + key, null, "FIXED", eventKey, null, List.of("shipper"), null);
    }
}
