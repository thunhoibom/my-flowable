package io.github.illuseahashmap.serviceregistry.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.illuseahashmap.serviceregistry.domain.AuthConfig;
import io.github.illuseahashmap.serviceregistry.domain.AuthMode;
import io.github.illuseahashmap.serviceregistry.domain.AuthType;
import io.github.illuseahashmap.serviceregistry.domain.EnvironmentBaseUrl;
import io.github.illuseahashmap.serviceregistry.domain.HttpMethod;
import io.github.illuseahashmap.serviceregistry.domain.InMemoryServiceCatalog;
import io.github.illuseahashmap.serviceregistry.domain.OutboundRequest;
import io.github.illuseahashmap.serviceregistry.domain.PublishedOperation;
import io.github.illuseahashmap.serviceregistry.domain.ServiceCaller;
import io.github.illuseahashmap.serviceregistry.domain.TransportResponse;
import io.github.illuseahashmap.serviceregistry.engine.InMemoryServiceRepositoryService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PublishedServiceInvokerTest {

    private static final String TENANT = "tenant-a";

    private final InMemoryServiceCatalog catalog = new InMemoryServiceCatalog();
    private final InMemoryServiceRepositoryService repository = new InMemoryServiceRepositoryService();
    private final AtomicReference<OutboundRequest> captured = new AtomicReference<>();

    @Test
    void callsTheEnvironmentBaseUrlWithTheSharedSecret() {
        catalog.saveService("crm", "CRM");
        catalog.saveServiceSettings("crm", "CRM", "Khách hàng",
                List.of(new EnvironmentBaseUrl("default", "https://crm.example")),
                new AuthConfig(AuthType.BEARER, null, "shared-token"));
        catalog.saveOperation("crm", operation("createLead", "/leads", AuthMode.INHERIT));
        publishAndDeploy("p1", "crm");

        invoker().invoke(TENANT, "p1", "crm", "createLead", Map.of());

        assertEquals("https://crm.example/leads", captured.get().url());
        assertEquals("Bearer shared-token", captured.get().headers().get("Authorization"));
    }

    @Test
    void theDeployedDesignWinsButBaseUrlAndSecretAreTheCurrentOnes() {
        catalog.saveService("crm", "CRM");
        catalog.saveServiceSettings("crm", "CRM", null,
                List.of(new EnvironmentBaseUrl("default", "https://old.example")),
                new AuthConfig(AuthType.BEARER, null, "old-token"));
        catalog.saveOperation("crm", operation("createLead", "/v1/leads", AuthMode.INHERIT));
        publishAndDeploy("p1", "crm");

        catalog.saveServiceSettings("crm", "CRM", null,
                List.of(new EnvironmentBaseUrl("default", "https://new.example")),
                new AuthConfig(AuthType.BEARER, null, "new-token"));
        catalog.saveOperation("crm", operation("createLead", "/v2/leads", AuthMode.INHERIT));
        catalog.publish("crm");

        invoker().invoke(TENANT, "p1", "crm", "createLead", Map.of());

        assertEquals("https://new.example/v1/leads", captured.get().url());
        assertEquals("Bearer new-token", captured.get().headers().get("Authorization"));
    }

    @Test
    void anOperationMissingFromTheParentDeploymentIsNotPublished() {
        catalog.saveService("crm", "CRM");
        catalog.saveOperation("crm", operation("ping", "https://other.example/ping", AuthMode.NONE));
        publishAndDeploy("p1", "crm");

        assertTrue(invoker().findPublished(TENANT, "p1", "crm", "other").isEmpty());
        assertTrue(invoker().findPublished(TENANT, "p2", "crm", "ping").isEmpty());
        ServiceCallException error = assertThrows(ServiceCallException.class,
                () -> invoker().invoke(TENANT, "p2", "crm", "ping", Map.of()));
        assertEquals("SERVICE_NOT_PUBLISHED", error.getMessage());
    }

    @Test
    void rejectsARelativePathWhenTheEnvironmentHasNoBaseUrl() {
        catalog.saveService("crm", "CRM");
        catalog.saveOperation("crm", operation("createLead", "/leads", AuthMode.INHERIT));
        publishAndDeploy("p1", "crm");

        ServiceCallException error = assertThrows(ServiceCallException.class,
                () -> invoker().invoke(TENANT, "p1", "crm", "createLead", Map.of()));
        assertEquals("SERVICE_NOT_PUBLISHED", error.getMessage());
    }

    @Test
    void callsAnAbsoluteUrlWithoutABaseUrl() {
        catalog.saveService("crm", "CRM");
        catalog.saveOperation("crm", operation("ping", "https://other.example/ping", AuthMode.NONE));
        publishAndDeploy("p1", "crm");

        invoker().invoke(TENANT, "p1", "crm", "ping", Map.of());

        assertEquals("https://other.example/ping", captured.get().url());
    }

    private void publishAndDeploy(String parentDeploymentId, String serviceKey) {
        catalog.publish(serviceKey);
        repository.deploy(parentDeploymentId, catalog.publishedService(serviceKey).orElseThrow());
    }

    private PublishedServiceInvoker invoker() {
        return new PublishedServiceInvoker(repository, catalog, new ServiceCaller(request -> {
            captured.set(request);
            return new TransportResponse(204, "");
        }), () -> "default");
    }

    private static PublishedOperation operation(String key, String url, AuthMode mode) {
        return new PublishedOperation(
                key, HttpMethod.GET, url,
                mode, AuthType.NONE, null, null, null,
                1000, 1000,
                List.of(), List.of(), List.of(), List.of());
    }
}
