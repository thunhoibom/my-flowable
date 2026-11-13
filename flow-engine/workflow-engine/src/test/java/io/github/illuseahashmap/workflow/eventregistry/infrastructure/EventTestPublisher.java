package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import static org.mockito.Mockito.mock;

import io.github.illuseahashmap.workflow.config.EventRegistryProperties;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventDeploymentRequest;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventFieldView;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Deploys events and channels the way Flow Admin BE does, through the receiving side of the Deployment API. */
final class EventTestPublisher {

    static final TenantContext.TenantInfo TENANT = new TenantContext.TenantInfo("t1", "t1", "Tenant 1");
    static final CurrentPrincipal DESIGNER = new CurrentPrincipal("SERVICE", "flow-admin-be", "designer", "Designer",
            "t1", Set.of(), Set.of());
    static final EventFieldView ORDER_ID = new EventFieldView("orderId", "string", true, false, false);
    static final EventFieldView AMOUNT = new EventFieldView("amount", "double", false, false, false);

    private final EventDeploymentServiceImpl deployments;

    EventTestPublisher(EventRegistryTestEngine test, EventRegistryProperties properties) {
        deployments = new EventDeploymentServiceImpl(test.gateway, new InMemoryEventRegistryRepositories().references,
                mock(WorkflowOperationAuditService.class), () -> TENANT, properties);
    }

    void event(String key, EventFieldView... fields) {
        deployments.deploy(new EventDeploymentRequest(UUID.randomUUID().toString(), null,
                List.of(new EventDeploymentRequest.Event(key, key, null, List.of(fields))), List.of()));
    }

    void channel(String key, String eventKey, String caller) {
        deployments.deploy(new EventDeploymentRequest(UUID.randomUUID().toString(), null, List.of(),
                List.of(new EventDeploymentRequest.Channel(key, key, null, "FIXED", eventKey, null,
                        List.of(caller), List.of()))));
    }
}
