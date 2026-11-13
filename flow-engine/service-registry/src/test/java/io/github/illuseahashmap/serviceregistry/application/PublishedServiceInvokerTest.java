package io.github.illuseahashmap.serviceregistry.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.illuseahashmap.serviceregistry.domain.AuthConfig;
import io.github.illuseahashmap.serviceregistry.domain.AuthMode;
import io.github.illuseahashmap.serviceregistry.domain.AuthType;
import io.github.illuseahashmap.serviceregistry.domain.HttpMethod;
import io.github.illuseahashmap.serviceregistry.domain.OutboundRequest;
import io.github.illuseahashmap.serviceregistry.domain.PublishedOperation;
import io.github.illuseahashmap.serviceregistry.domain.PublishedService;
import io.github.illuseahashmap.serviceregistry.domain.ServiceCaller;
import io.github.illuseahashmap.serviceregistry.domain.ServiceSettings;
import io.github.illuseahashmap.serviceregistry.domain.TransportResponse;
import io.github.illuseahashmap.serviceregistry.engine.InMemoryServiceRepositoryService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The design comes from the deployment, base URL and credentials from the environment settings of the engine. */
class PublishedServiceInvokerTest {

    private static final String TENANT = "tenant-a";

    private final InMemoryServiceRepositoryService repository = new InMemoryServiceRepositoryService();
    private final AtomicReference<OutboundRequest> captured = new AtomicReference<>();
    private final Map<String, ServiceSettings> environment = new HashMap<>();

    @Test
    void callsTheEnvironmentBaseUrlWithTheSharedSecret() {
        deploy("p1", service(operation("createLead", "/leads", AuthMode.INHERIT)));
        environment.put("crm", new ServiceSettings("https://crm.example",
                new AuthConfig(AuthType.BEARER, null, "shared-token"), Map.of()));

        invoker().invoke(TENANT, "p1", "crm", "createLead", Map.of());

        assertEquals("https://crm.example/leads", captured.get().url());
        assertEquals("Bearer shared-token", captured.get().headers().get("Authorization"));
    }

    @Test
    void theDeployedDesignWinsButBaseUrlAndSecretAreTheCurrentOnes() {
        deploy("p1", service(operation("createLead", "/v1/leads", AuthMode.INHERIT)));
        deploy("p2", service(operation("createLead", "/v2/leads", AuthMode.INHERIT)));
        environment.put("crm", new ServiceSettings("https://new.example",
                new AuthConfig(AuthType.BEARER, null, "new-token"), Map.of()));

        invoker().invoke(TENANT, "p1", "crm", "createLead", Map.of());

        assertEquals("https://new.example/v1/leads", captured.get().url());
        assertEquals("Bearer new-token", captured.get().headers().get("Authorization"));
    }

    @Test
    void operationCredentialsOfTheEnvironmentOverrideTheDeployedMode() {
        deploy("p1", service(operation("createLead", "/leads", AuthMode.INHERIT),
                operation("audit", "/audit", AuthMode.INHERIT)));
        environment.put("crm", new ServiceSettings("https://env.example",
                new AuthConfig(AuthType.BEARER, null, "env-token"),
                Map.of("audit", new ServiceSettings.OperationAuth(AuthMode.OVERRIDE,
                        new AuthConfig(AuthType.API_KEY, "X-Key", "audit-key")))));

        invoker().invoke(TENANT, "p1", "crm", "audit", Map.of());

        assertEquals("audit-key", captured.get().headers().get("X-Key"));
        assertNull(captured.get().headers().get("Authorization"));
        assertTrue(invoker().findPublished(TENANT, "p1", "crm", "audit").isPresent());
    }

    @Test
    void anOperationMissingFromTheParentDeploymentIsNotPublished() {
        deploy("p1", service(operation("ping", "https://other.example/ping", AuthMode.NONE)));

        assertTrue(invoker().findPublished(TENANT, "p1", "crm", "other").isEmpty());
        assertTrue(invoker().findPublished(TENANT, "p2", "crm", "ping").isEmpty());
        ServiceCallException error = assertThrows(ServiceCallException.class,
                () -> invoker().invoke(TENANT, "p2", "crm", "ping", Map.of()));
        assertEquals("SERVICE_NOT_PUBLISHED", error.getMessage());
    }

    @Test
    void rejectsARelativePathWhenTheEnvironmentHasNoBaseUrl() {
        deploy("p1", service(operation("createLead", "/leads", AuthMode.INHERIT)));

        ServiceCallException error = assertThrows(ServiceCallException.class,
                () -> invoker().invoke(TENANT, "p1", "crm", "createLead", Map.of()));
        assertEquals("SERVICE_NOT_PUBLISHED", error.getMessage());
    }

    @Test
    void callsAnAbsoluteUrlWithoutEnvironmentSettings() {
        deploy("p1", service(operation("ping", "https://other.example/ping", AuthMode.NONE)));

        invoker().invoke(TENANT, "p1", "crm", "ping", Map.of());

        assertEquals("https://other.example/ping", captured.get().url());
    }

    private void deploy(String parentDeploymentId, PublishedService service) {
        repository.deploy(parentDeploymentId, service);
    }

    private PublishedServiceInvoker invoker() {
        ServiceSettingsSource settings = (tenantId, serviceKey) ->
                TENANT.equals(tenantId) ? Optional.ofNullable(environment.get(serviceKey)) : Optional.empty();
        return new PublishedServiceInvoker(repository, settings, new ServiceCaller(request -> {
            captured.set(request);
            return new TransportResponse(204, "");
        }));
    }

    private static PublishedService service(PublishedOperation... operations) {
        return new PublishedService("crm", "CRM", null, List.of(), null, List.of(operations));
    }

    private static PublishedOperation operation(String key, String url, AuthMode mode) {
        return new PublishedOperation(
                key, HttpMethod.GET, url,
                mode, AuthType.NONE, null, null, null,
                1000, 1000,
                List.of(), List.of(), List.of(), List.of());
    }
}
