package io.github.illuseahashmap.serviceregistry.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.designhub.engine.FlowEngineClient;
import io.github.illuseahashmap.designhub.engine.FlowEngineProperties;
import io.github.illuseahashmap.serviceregistry.domain.AuthConfig;
import io.github.illuseahashmap.serviceregistry.domain.EnvironmentBaseUrl;
import io.github.illuseahashmap.serviceregistry.domain.PublishedOperation;
import io.github.illuseahashmap.serviceregistry.domain.PublishedService;
import io.github.illuseahashmap.serviceregistry.domain.ServiceCatalog;
import io.github.illuseahashmap.serviceregistry.domain.ServiceSettings;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Transitional, until Hub edits environment settings (phase 3): when a service is published in the catalog, its
 * base URL for the environment of the Flow Engine and its credentials are written to that engine's
 * {@code work_service_environment} through the Admin API, as the current user. The design itself travels with each
 * process deployment.
 */
@Component
public class ServiceEnvironmentSync {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final ServiceCatalog catalog;
    private final FlowEngineClient engine;
    private final FlowEngineProperties properties;
    private final ObjectMapper json = new ObjectMapper();

    public ServiceEnvironmentSync(ServiceCatalog catalog, FlowEngineClient engine, FlowEngineProperties properties) {
        this.catalog = catalog;
        this.engine = engine;
        this.properties = properties;
    }

    public void push(String serviceKey) {
        PublishedService service = catalog.publishedService(serviceKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Dịch vụ " + serviceKey + " chưa publish"));
        engine.saveServiceEnvironment(serviceKey, json.convertValue(settings(service), MAP));
    }

    ServiceSettings settings(PublishedService service) {
        String baseUrl = service.baseUrls().stream()
                .filter(item -> properties.environment().equals(item.environment()))
                .map(EnvironmentBaseUrl::url)
                .filter(url -> url != null && !url.isBlank())
                .findFirst()
                .orElse(null);
        Map<String, ServiceSettings.OperationAuth> operations = new LinkedHashMap<>();
        for (PublishedOperation operation : service.operations()) {
            operations.put(operation.operationKey(), new ServiceSettings.OperationAuth(operation.authMode(),
                    new AuthConfig(operation.authType(), operation.apiKeyHeader(), operation.secret(),
                            operation.apiKeyLocation(), operation.authDetails())));
        }
        return new ServiceSettings(baseUrl, service.sharedAuth(), operations);
    }
}
