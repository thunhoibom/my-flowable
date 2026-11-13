package io.github.illuseahashmap.workflow.serviceregistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.illuseahashmap.serviceregistry.domain.ServiceSettings;
import io.github.illuseahashmap.serviceregistry.engine.ServiceModelJson;
import io.github.illuseahashmap.serviceregistry.domain.PublishedService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ServiceDeploymentSupportAdapterTest {

    private final ServiceDeploymentSupportAdapter adapter = new ServiceDeploymentSupportAdapter(
            (tenantId, serviceKey) -> "crm".equals(serviceKey)
                    ? Optional.of(new ServiceSettings("https://crm.example", null, Map.of()))
                    : Optional.empty());

    @Test
    void keepsOnlyTheDesignOfAServiceSentWithADeployment() {
        Map<String, Object> sent = Map.of(
                "serviceKey", "crm",
                "name", "CRM",
                "baseUrls", List.of(Map.of("environment", "default", "url", "https://leak.example")),
                "sharedAuth", Map.of("type", "BEARER", "secret", "leaked-token"),
                "operations", List.of(Map.of("operationKey", "createLead", "method", "POST",
                        "urlTemplate", "/leads", "authMode", "OVERRIDE", "authType", "API_KEY",
                        "secret", "leaked-key", "connectTimeoutMillis", 1000, "readTimeoutMillis", 1000)));

        byte[] content = adapter.designOnly("crm", sent);

        String json = new String(content, StandardCharsets.UTF_8);
        assertThat(json).doesNotContain("leak");
        PublishedService service = new ServiceModelJson().read(content);
        assertThat(service.baseUrls()).isEmpty();
        assertThat(service.operations().getFirst().urlTemplate()).isEqualTo("/leads");
        assertThat(service.operations().getFirst().secret()).isNull();
    }

    @Test
    void rejectsAnUnreadableMismatchedOrEmptyDefinition() {
        assertThatThrownBy(() -> adapter.designOnly("crm", Map.of("serviceKey", "erp",
                "operations", List.of(Map.of("operationKey", "x")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("serviceKey");
        assertThatThrownBy(() -> adapter.designOnly("crm", Map.of("serviceKey", "crm")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("operation");
        assertThatThrownBy(() -> adapter.designOnly("crm", Map.of("serviceKey", "crm", "operations", "nope")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listsTheServicesWithoutEnvironmentSettings() {
        assertThat(adapter.withoutEnvironment("t1", List.of("crm", "erp", "billing")))
                .containsExactly("billing", "erp");
    }
}
