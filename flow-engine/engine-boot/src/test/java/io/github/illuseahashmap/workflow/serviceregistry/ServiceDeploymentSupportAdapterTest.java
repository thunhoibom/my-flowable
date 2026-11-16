package io.github.illuseahashmap.workflow.serviceregistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.illuseahashmap.serviceregistry.application.ServiceEnvironment;
import io.github.illuseahashmap.serviceregistry.domain.OauthClientCredentials;
import io.github.illuseahashmap.serviceregistry.domain.PublishedService;
import io.github.illuseahashmap.serviceregistry.engine.ServiceModelJson;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ServiceDeploymentSupportAdapterTest {

    private final ServiceDeploymentSupportAdapter adapter = new ServiceDeploymentSupportAdapter(new ServiceEnvironment() {
        @Override
        public Optional<String> secret(String tenantId, String name) {
            return Optional.empty();
        }

        @Override
        public Optional<OauthClientCredentials.Client> oauth2Client(String tenantId, String registrationKey) {
            return Optional.empty();
        }

        @Override
        public Set<String> missingSecrets(String tenantId, Collection<String> names) {
            return without(names, "crmToken");
        }

        @Override
        public Set<String> missingOauth2Clients(String tenantId, Collection<String> registrationKeys) {
            return without(registrationKeys, "crm-oauth");
        }

        @Override
        public Set<String> missingTenantVariables(String tenantId, Collection<String> names) {
            return without(names, "crmBaseUrl");
        }
    });

    @Test
    void keepsTheModelWithItsReferencesAndDropsResolvedCredentials() {
        Map<String, Object> sent = Map.of(
                "serviceKey", "crm",
                "name", "CRM",
                "baseUrl", "${tenant.crmBaseUrl}",
                "authorization", Map.of("type", "BEARER", "value", Map.of("source", "SECRET", "value", "crmToken")),
                "operations", List.of(Map.of("operationKey", "createLead", "method", "POST",
                        "urlTemplate", "/leads", "authMode", "INHERIT", "authType", "API_KEY",
                        "secret", "leaked-key", "connectTimeoutMillis", 1000, "readTimeoutMillis", 1000)));

        byte[] content = adapter.serviceResource("crm", sent);

        String json = new String(content, StandardCharsets.UTF_8);
        assertThat(json).doesNotContain("leaked-key");
        PublishedService service = new ServiceModelJson().read(content);
        assertThat(service.baseUrl()).isEqualTo("${tenant.crmBaseUrl}");
        assertThat(service.authorization().secrets()).containsExactly("crmToken");
        assertThat(service.operations().getFirst().urlTemplate()).isEqualTo("/leads");
        assertThat(service.operations().getFirst().secret()).isNull();
    }

    @Test
    void rejectsAnUnreadableMismatchedEmptyOrLiteralSecretDefinition() {
        assertThatThrownBy(() -> adapter.serviceResource("crm", Map.of("serviceKey", "erp",
                "operations", List.of(Map.of("operationKey", "x")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("serviceKey");
        assertThatThrownBy(() -> adapter.serviceResource("crm", Map.of("serviceKey", "crm")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("operation");
        assertThatThrownBy(() -> adapter.serviceResource("crm", Map.of("serviceKey", "crm", "operations", "nope")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.serviceResource("crm", Map.of("serviceKey", "crm",
                "authorization", Map.of("type", "BEARER", "value", Map.of("source", "VALUE", "value", "token")),
                "operations", List.of(Map.of("operationKey", "x")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("token");
    }

    @Test
    void listsWhatTheServicesReferenceAndThisEngineLacks() {
        byte[] complete = adapter.serviceResource("crm", Map.of("serviceKey", "crm", "baseUrl", "${tenant.crmBaseUrl}",
                "authorization", Map.of("type", "BEARER", "value", Map.of("source", "SECRET", "value", "crmToken")),
                "operations", List.of(Map.of("operationKey", "x"))));
        byte[] incomplete = adapter.serviceResource("erp", Map.of("serviceKey", "erp", "baseUrl", "${tenant.erpUrl}",
                "authorization", Map.of("type", "BEARER",
                        "value", Map.of("source", "OAUTH2_CLIENT", "value", "erp-oauth")),
                "operations", List.of(Map.of("operationKey", "x", "authMode", "OVERRIDE",
                        "authorization", Map.of("type", "API_KEY", "headerName", "X-Key",
                                "value", Map.of("source", "SECRET", "value", "erpKey"))))));

        assertThat(adapter.missingConfiguration("t1", Map.of("crm", complete))).isEmpty();
        assertThat(adapter.missingConfiguration("t1", Map.of("crm", complete, "erp", incomplete)))
                .containsExactly("secret erpKey", "OAuth2 client erp-oauth", "biến tenant erpUrl");
    }

    private static Set<String> without(Collection<String> names, String existing) {
        Set<String> missing = new TreeSet<>(names);
        missing.remove(existing);
        return missing;
    }
}
