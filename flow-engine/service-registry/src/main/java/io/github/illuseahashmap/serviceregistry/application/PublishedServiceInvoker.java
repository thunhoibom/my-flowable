package io.github.illuseahashmap.serviceregistry.application;

import io.github.illuseahashmap.serviceregistry.domain.AuthConfig;
import io.github.illuseahashmap.serviceregistry.domain.AuthInheritance;
import io.github.illuseahashmap.serviceregistry.domain.Authorization;
import io.github.illuseahashmap.serviceregistry.domain.CallOutcome;
import io.github.illuseahashmap.serviceregistry.domain.PublishedOperation;
import io.github.illuseahashmap.serviceregistry.domain.PublishedService;
import io.github.illuseahashmap.serviceregistry.domain.ServiceCaller;
import io.github.illuseahashmap.serviceregistry.engine.ServiceDefinitionNotFoundException;
import io.github.illuseahashmap.serviceregistry.engine.ServiceRepositoryService;
import java.util.Map;
import java.util.Optional;

/**
 * Calls a service the way the running process was deployed with it, as Flowable's service registry does: the model
 * (operations, base URL, authorization) comes from the service engine, in the parent deployment of the process.
 * What differs per environment comes from this Flow Engine at call time: {@code rest-settings} overrides of the base
 * URL and timeout, secrets, OAuth2 clients and tenant variables ({@code ${tenant.<name>}}).
 */
public final class PublishedServiceInvoker {

    private final ServiceRepositoryService repository;
    private final CredentialResolver credentials;
    private final RestSettings restSettings;
    private final ServiceCaller caller;

    public PublishedServiceInvoker(ServiceRepositoryService repository, CredentialResolver credentials,
                                   RestSettings restSettings, ServiceCaller caller) {
        this.repository = repository;
        this.credentials = credentials;
        this.restSettings = restSettings == null ? RestSettings.NONE : restSettings;
        this.caller = caller;
    }

    /** The deployed operation (its contract), without credentials. */
    public Optional<PublishedOperation> findPublished(String tenantId, String parentDeploymentId,
                                                      String serviceKey, String operationKey) {
        return definition(tenantId, parentDeploymentId, serviceKey)
                .flatMap(definition -> operation(definition, operationKey));
    }

    public Map<String, Object> invoke(String tenantId, String parentDeploymentId, String serviceKey,
                                      String operationKey, Map<String, Object> resolvedInputs,
                                      ExpressionEvaluator expressions) {
        PublishedService definition = definition(tenantId, parentDeploymentId, serviceKey)
                .orElseThrow(() -> new ServiceCallException("SERVICE_NOT_PUBLISHED"));
        PublishedOperation operation = operation(definition, operationKey)
                .orElseThrow(() -> new ServiceCallException("SERVICE_NOT_PUBLISHED"));
        ExpressionEvaluator evaluator = expressions == null ? ExpressionEvaluator.NONE : expressions;
        PublishedOperation timed = restSettings.timeout(serviceKey)
                .map(operation::withReadTimeout)
                .orElse(operation);
        Authorization authorization = AuthInheritance.resolve(
                operation.authMode(), definition.authorization(), operation.authorization());
        AuthConfig resolved = credentials.resolve(tenantId, authorization, evaluator, timed.timeoutMillis());
        // RequestFactory does not know about the service, so the resolved credentials go on the operation.
        PublishedOperation effective = timed.withCredentials(operation.authMode(), resolved);
        CallOutcome outcome = caller.call(effective, resolvedInputs == null ? Map.of() : resolvedInputs,
                baseUrl(serviceKey, definition, operation, evaluator));
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

    /** Service override, then {@code default} override, then the model; an absolute operation URL needs none. */
    private String baseUrl(String serviceKey, PublishedService service, PublishedOperation operation,
                           ExpressionEvaluator evaluator) {
        if (isAbsolute(operation.urlTemplate())) {
            return null;
        }
        String configured = restSettings.baseUrl(serviceKey).orElse(service.baseUrl());
        String url = configured == null ? null : evaluator.text(configured.trim());
        if (url == null || url.isBlank()) {
            throw new ServiceCallException("SERVICE_NOT_PUBLISHED");
        }
        return url;
    }

    private static boolean isAbsolute(String path) {
        return path != null && (path.startsWith("https://") || path.startsWith("http://"));
    }
}
