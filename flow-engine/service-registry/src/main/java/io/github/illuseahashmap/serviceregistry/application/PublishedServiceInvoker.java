package io.github.illuseahashmap.serviceregistry.application;

import io.github.illuseahashmap.serviceregistry.domain.AuthConfig;
import io.github.illuseahashmap.serviceregistry.domain.AuthInheritance;
import io.github.illuseahashmap.serviceregistry.domain.AuthMode;
import io.github.illuseahashmap.serviceregistry.domain.AuthType;
import io.github.illuseahashmap.serviceregistry.domain.CallOutcome;
import io.github.illuseahashmap.serviceregistry.domain.PublishedOperation;
import io.github.illuseahashmap.serviceregistry.domain.PublishedService;
import io.github.illuseahashmap.serviceregistry.domain.ServiceCaller;
import io.github.illuseahashmap.serviceregistry.domain.ServiceSettings;
import io.github.illuseahashmap.serviceregistry.engine.ServiceDefinitionNotFoundException;
import io.github.illuseahashmap.serviceregistry.engine.ServiceRepositoryService;
import java.util.Map;
import java.util.Optional;

/**
 * Calls a service the way the running process was deployed with it. The design (operations, parameters, body,
 * error rules, steps) comes from the service engine, in the parent deployment of the process. Base URLs and
 * credentials are environment settings and come from the current configuration of the service on this
 * Flow Engine ({@link ServiceSettingsSource}), so changing them takes effect on the next call.
 */
public final class PublishedServiceInvoker {

    private static final AuthConfig NO_AUTH = new AuthConfig(AuthType.NONE, null, null);

    private final ServiceRepositoryService repository;
    private final ServiceSettingsSource settingsSource;
    private final ServiceCaller caller;

    public PublishedServiceInvoker(ServiceRepositoryService repository, ServiceSettingsSource settingsSource,
                                   ServiceCaller caller) {
        this.repository = repository;
        this.settingsSource = settingsSource;
        this.caller = caller;
    }

    /** The operation with its credentials resolved, so a bearer variable name is visible before the call. */
    public Optional<PublishedOperation> findPublished(String tenantId, String parentDeploymentId,
                                                      String serviceKey, String operationKey) {
        return definition(tenantId, parentDeploymentId, serviceKey)
                .flatMap(definition -> operation(definition, operationKey)
                        .map(operation -> withCredentials(operation, settingsSource.find(tenantId, serviceKey))));
    }

    public Map<String, Object> invoke(String tenantId, String parentDeploymentId, String serviceKey,
                                      String operationKey, Map<String, Object> resolvedInputs) {
        PublishedService definition = definition(tenantId, parentDeploymentId, serviceKey)
                .orElseThrow(() -> new ServiceCallException("SERVICE_NOT_PUBLISHED"));
        PublishedOperation operation = operation(definition, operationKey)
                .orElseThrow(() -> new ServiceCallException("SERVICE_NOT_PUBLISHED"));
        Optional<ServiceSettings> settings = settingsSource.find(tenantId, serviceKey);
        // RequestFactory does not know about the service, so the resolved credentials go on the operation.
        PublishedOperation effective = withCredentials(operation, settings);
        CallOutcome outcome = caller.call(effective, resolvedInputs == null ? Map.of() : resolvedInputs,
                baseUrl(operation, settings));
        if (outcome.failed()) {
            throw new ServiceCallException(outcome.bpmnErrorCode());
        }
        return outcome.variables();
    }

    private Optional<PublishedService> definition(String tenantId, String parentDeploymentId, String serviceKey) {
        try {
            return Optional.of(repository.getServiceModelByKeyAndParentDeploymentId(
                    serviceKey, parentDeploymentId, tenantId));
        } catch (ServiceDefinitionNotFoundException exception) {
            return Optional.empty();
        }
    }

    private static Optional<PublishedOperation> operation(PublishedService service, String operationKey) {
        return service.operations().stream()
                .filter(operation -> operation.operationKey().equals(operationKey))
                .findFirst();
    }

    /**
     * Credentials of the current configuration. The deployed auth mode decides between the shared and the
     * operation credentials when the configuration does not set a mode for the operation.
     */
    private static PublishedOperation withCredentials(PublishedOperation operation,
                                                      Optional<ServiceSettings> settings) {
        AuthConfig shared = settings.map(ServiceSettings::sharedAuth).orElse(NO_AUTH);
        Optional<ServiceSettings.OperationAuth> configured =
                settings.map(current -> current.operations().get(operation.operationKey()));
        AuthMode mode = configured.map(ServiceSettings.OperationAuth::mode).orElse(operation.authMode());
        AuthConfig own = configured.map(ServiceSettings.OperationAuth::auth).orElse(NO_AUTH);
        return operation.withCredentials(AuthMode.OVERRIDE, AuthInheritance.resolve(mode, shared, own));
    }

    private static String baseUrl(PublishedOperation operation, Optional<ServiceSettings> settings) {
        if (isAbsolute(operation.urlTemplate())) {
            return null;
        }
        return settings.map(ServiceSettings::baseUrl)
                .filter(url -> !url.isBlank())
                .orElseThrow(() -> new ServiceCallException("SERVICE_NOT_PUBLISHED"));
    }

    private static boolean isAbsolute(String path) {
        return path != null && (path.startsWith("https://") || path.startsWith("http://"));
    }
}
