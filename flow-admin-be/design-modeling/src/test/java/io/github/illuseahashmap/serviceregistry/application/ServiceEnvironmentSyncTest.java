package io.github.illuseahashmap.serviceregistry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.designhub.engine.FlowEngineClient;
import io.github.illuseahashmap.designhub.engine.FlowEngineProperties;
import io.github.illuseahashmap.serviceregistry.domain.EnvironmentBaseUrl;
import io.github.illuseahashmap.serviceregistry.domain.PublishedService;
import io.github.illuseahashmap.serviceregistry.domain.ServiceCatalog;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The catalog seeds the settings of a service once; afterwards Hub owns them. */
class ServiceEnvironmentSyncTest {

    private final ServiceCatalog catalog = mock(ServiceCatalog.class);
    private final FlowEngineClient engine = mock(FlowEngineClient.class);
    private final ServiceEnvironmentSync sync = new ServiceEnvironmentSync(catalog, engine,
            new FlowEngineProperties("http://engine", "flow-admin-be", "s", 1, "dev", Duration.ofSeconds(1),
                    Duration.ofSeconds(1), null));

    @Test
    @SuppressWarnings("unchecked")
    void writesTheBaseUrlOfThisEnvironmentWhenFlowEngineHasNoSettings() {
        when(engine.hasServiceEnvironment("crm")).thenReturn(false);
        when(catalog.publishedService("crm")).thenReturn(Optional.of(new PublishedService("crm", "CRM", null,
                List.of(new EnvironmentBaseUrl("prod", "https://crm.prod"),
                        new EnvironmentBaseUrl("dev", "https://crm.dev")), null, List.of())));

        assertThat(sync.seed("crm")).isTrue();

        ArgumentCaptor<Map<String, Object>> settings = ArgumentCaptor.forClass(Map.class);
        verify(engine).saveServiceEnvironment(eq("crm"), settings.capture());
        assertThat(settings.getValue()).containsEntry("baseUrl", "https://crm.dev");
    }

    @Test
    void neverOverwritesWhatHubChanged() {
        when(engine.hasServiceEnvironment("crm")).thenReturn(true);

        assertThat(sync.seed("crm")).isFalse();

        verify(engine, never()).saveServiceEnvironment(any(), anyMap());
        verifyNoInteractions(catalog);
    }
}
