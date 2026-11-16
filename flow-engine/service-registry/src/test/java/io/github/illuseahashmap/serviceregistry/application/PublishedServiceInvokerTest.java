package io.github.illuseahashmap.serviceregistry.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.illuseahashmap.serviceregistry.domain.ApiKeyLocation;
import io.github.illuseahashmap.serviceregistry.domain.AuthMode;
import io.github.illuseahashmap.serviceregistry.domain.AuthType;
import io.github.illuseahashmap.serviceregistry.domain.Authorization;
import io.github.illuseahashmap.serviceregistry.domain.CredentialSource;
import io.github.illuseahashmap.serviceregistry.domain.CredentialValue;
import io.github.illuseahashmap.serviceregistry.domain.HttpMethod;
import io.github.illuseahashmap.serviceregistry.domain.InvalidServiceRequestException;
import io.github.illuseahashmap.serviceregistry.domain.OauthClientCredentials;
import io.github.illuseahashmap.serviceregistry.domain.OutboundRequest;
import io.github.illuseahashmap.serviceregistry.domain.PublishedOperation;
import io.github.illuseahashmap.serviceregistry.domain.PublishedService;
import io.github.illuseahashmap.serviceregistry.domain.ServiceCaller;
import io.github.illuseahashmap.serviceregistry.domain.TransportResponse;
import io.github.illuseahashmap.serviceregistry.engine.InMemoryServiceRepositoryService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The model (base URL, authorization) comes from the deployment; what differs per environment comes from this
 * Flow Engine at call time: rest-settings overrides, secrets, OAuth2 clients and expressions.
 */
class PublishedServiceInvokerTest {

    private static final String TENANT = "tenant-a";

    private final InMemoryServiceRepositoryService repository = new InMemoryServiceRepositoryService();
    private final List<OutboundRequest> sent = new ArrayList<>();
    private final Map<String, String> secrets = new HashMap<>();
    private final Map<String, OauthClientCredentials.Client> clients = new HashMap<>();
    private final Map<String, String> tenantVariables = new HashMap<>();
    private RestSettings restSettings = RestSettings.NONE;

    @Test
    void usesTheModelBaseUrlAndASecretBearerToken() {
        deploy("p1", service("https://crm.example", bearer(CredentialSource.SECRET, "crmToken"),
                operation("createLead", "/leads", AuthMode.INHERIT)));
        secrets.put("crmToken", "shared-token");

        invoker().invoke(TENANT, "p1", "crm", "createLead", Map.of(), this::evaluate);

        assertEquals("https://crm.example/leads", last().url());
        assertEquals("Bearer shared-token", last().headers().get("Authorization"));
    }

    @Test
    void baseUrlExpressionReadsATenantVariable() {
        deploy("p1", service("${tenant.crmBaseUrl}/api", Authorization.NONE,
                operation("createLead", "/leads", AuthMode.INHERIT)));
        tenantVariables.put("crmBaseUrl", "https://crm.dev");

        invoker().invoke(TENANT, "p1", "crm", "createLead", Map.of(), this::evaluate);

        assertEquals("https://crm.dev/api/leads", last().url());
    }

    @Test
    void restSettingsOverrideTheModelServiceFirstThenDefault() {
        deploy("p1", service("https://model.example", Authorization.NONE,
                operation("createLead", "/leads", AuthMode.INHERIT)));
        restSettings = new RestSettings(Map.of(RestSettings.DEFAULT, new RestSettings.Setting("https://default.example", null)));
        invoker().invoke(TENANT, "p1", "crm", "createLead", Map.of(), this::evaluate);
        assertEquals("https://default.example/leads", last().url());

        restSettings = new RestSettings(Map.of(
                RestSettings.DEFAULT, new RestSettings.Setting("https://default.example", 900),
                "crm", new RestSettings.Setting("https://crm.override", 1500)));
        invoker().invoke(TENANT, "p1", "crm", "createLead", Map.of(), this::evaluate);
        assertEquals("https://crm.override/leads", last().url());
        assertEquals(1500, last().timeoutMillis());
    }

    @Test
    void operationOverrideAndNoneChooseTheirOwnAuthorization() {
        Authorization apiKey = new Authorization(AuthType.API_KEY, "X-Key", ApiKeyLocation.HEADER, null,
                new CredentialValue(CredentialSource.EXPRESSION, "${tenant.auditKey}"), null, null, null, null);
        deploy("p1", service("https://env.example", bearer(CredentialSource.SECRET, "crmToken"),
                operation("audit", "/audit", AuthMode.OVERRIDE).withAuthorization(AuthMode.OVERRIDE, apiKey),
                operation("ping", "/ping", AuthMode.NONE)));
        secrets.put("crmToken", "shared-token");
        tenantVariables.put("auditKey", "audit-key");

        invoker().invoke(TENANT, "p1", "crm", "audit", Map.of(), this::evaluate);
        assertEquals("audit-key", last().headers().get("X-Key"));
        assertNull(last().headers().get("Authorization"));

        invoker().invoke(TENANT, "p1", "crm", "ping", Map.of(), this::evaluate);
        assertNull(last().headers().get("Authorization"));
    }

    @Test
    void basicAndCustomHeaderResolveEachCredential() {
        Authorization basic = new Authorization(AuthType.BASIC, null, null,
                new CredentialValue(CredentialSource.VALUE, "svc"),
                new CredentialValue(CredentialSource.SECRET, "crmPassword"), null, null, null, null);
        Authorization header = new Authorization(AuthType.CUSTOM_HEADER, "X-Api-Token", null, null,
                new CredentialValue(CredentialSource.VALUE, "plain-value"), null, null, null, null);
        deploy("p1", service("https://env.example", basic,
                operation("read", "/read", AuthMode.INHERIT),
                operation("write", "/write", AuthMode.OVERRIDE).withAuthorization(AuthMode.OVERRIDE, header)));
        secrets.put("crmPassword", "p@ss");

        invoker().invoke(TENANT, "p1", "crm", "read", Map.of(), this::evaluate);
        assertEquals("Basic " + Base64.getEncoder().encodeToString("svc:p@ss".getBytes()),
                last().headers().get("Authorization"));

        invoker().invoke(TENANT, "p1", "crm", "write", Map.of(), this::evaluate);
        assertEquals("plain-value", last().headers().get("X-Api-Token"));
    }

    @Test
    void anOauth2ClientGivesTheBearerToken() {
        deploy("p1", service("https://env.example", bearer(CredentialSource.OAUTH2_CLIENT, "crm-oauth"),
                operation("createLead", "/leads", AuthMode.INHERIT)));
        clients.put("crm-oauth", new OauthClientCredentials.Client("https://idp.example/token", "crm", "s", List.of()));

        invoker().invoke(TENANT, "p1", "crm", "createLead", Map.of(), this::evaluate);

        assertEquals("https://idp.example/token", sent.get(0).url());
        assertEquals("Bearer issued-token", last().headers().get("Authorization"));
    }

    @Test
    void missingConfigurationIsAnInvalidRequest() {
        deploy("p1", service("https://env.example", bearer(CredentialSource.SECRET, "crmToken"),
                operation("createLead", "/leads", AuthMode.INHERIT)));

        InvalidServiceRequestException error = assertThrows(InvalidServiceRequestException.class,
                () -> invoker().invoke(TENANT, "p1", "crm", "createLead", Map.of(), this::evaluate));
        assertTrue(error.getMessage().contains("crmToken"));
        assertTrue(sent.isEmpty());
    }

    @Test
    void anOperationMissingFromTheParentDeploymentIsNotPublished() {
        deploy("p1", service(null, Authorization.NONE, operation("ping", "https://other.example/ping", AuthMode.NONE)));

        assertTrue(invoker().findPublished(TENANT, "p1", "crm", "other").isEmpty());
        assertTrue(invoker().findPublished(TENANT, "p2", "crm", "ping").isEmpty());
        ServiceCallException error = assertThrows(ServiceCallException.class,
                () -> invoker().invoke(TENANT, "p2", "crm", "ping", Map.of(), this::evaluate));
        assertEquals("SERVICE_NOT_PUBLISHED", error.getMessage());
    }

    @Test
    void rejectsARelativePathWithoutABaseUrl() {
        deploy("p1", service(null, Authorization.NONE, operation("createLead", "/leads", AuthMode.INHERIT)));

        ServiceCallException error = assertThrows(ServiceCallException.class,
                () -> invoker().invoke(TENANT, "p1", "crm", "createLead", Map.of(), this::evaluate));
        assertEquals("SERVICE_NOT_PUBLISHED", error.getMessage());
    }

    @Test
    void callsAnAbsoluteUrlWithoutABaseUrl() {
        deploy("p1", service(null, Authorization.NONE, operation("ping", "https://other.example/ping", AuthMode.NONE)));

        invoker().invoke(TENANT, "p1", "crm", "ping", Map.of(), null);

        assertEquals("https://other.example/ping", last().url());
    }

    /** Stands in for Flowable's expression manager: only {@code ${tenant.x}}, alone or at the start of a text. */
    private String evaluate(String expression) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\$\\{tenant\\.(\\w+)}(.*)")
                .matcher(expression);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(expression);
        }
        return tenantVariables.get(matcher.group(1)) + matcher.group(2);
    }

    private void deploy(String parentDeploymentId, PublishedService service) {
        repository.deploy(parentDeploymentId, service);
    }

    private OutboundRequest last() {
        return sent.get(sent.size() - 1);
    }

    private PublishedServiceInvoker invoker() {
        ServiceEnvironment environment = new ServiceEnvironment() {
            @Override
            public Optional<String> secret(String tenantId, String name) {
                return TENANT.equals(tenantId) ? Optional.ofNullable(secrets.get(name)) : Optional.empty();
            }

            @Override
            public Optional<OauthClientCredentials.Client> oauth2Client(String tenantId, String registrationKey) {
                return TENANT.equals(tenantId) ? Optional.ofNullable(clients.get(registrationKey)) : Optional.empty();
            }

            @Override
            public Set<String> missingSecrets(String tenantId, Collection<String> names) {
                return new TreeSet<>();
            }

            @Override
            public Set<String> missingOauth2Clients(String tenantId, Collection<String> registrationKeys) {
                return new TreeSet<>();
            }

            @Override
            public Set<String> missingTenantVariables(String tenantId, Collection<String> names) {
                return new TreeSet<>();
            }
        };
        io.github.illuseahashmap.serviceregistry.domain.HttpTransport transport = request -> {
            sent.add(request);
            if (request.url().endsWith("/token")) {
                return new TransportResponse(200, "{\"access_token\":\"issued-token\",\"expires_in\":3600}");
            }
            return new TransportResponse(204, "");
        };
        return new PublishedServiceInvoker(repository,
                new CredentialResolver(environment, new OauthClientCredentials(transport, Clock.systemUTC())),
                restSettings, new ServiceCaller(transport));
    }

    private static Authorization bearer(CredentialSource source, String value) {
        return new Authorization(AuthType.BEARER, null, null, null, new CredentialValue(source, value),
                null, null, null, null);
    }

    private static PublishedService service(String baseUrl, Authorization authorization,
                                            PublishedOperation... operations) {
        return new PublishedService("crm", "CRM", null, baseUrl, authorization, List.of(operations));
    }

    private static PublishedOperation operation(String key, String url, AuthMode mode) {
        return new PublishedOperation(
                key, HttpMethod.GET, url,
                mode, AuthType.NONE, null, null, null,
                1000, 1000,
                List.of(), List.of(), List.of(), List.of());
    }
}
