package io.github.illuseahashmap.serviceregistry.engine;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.serviceregistry.domain.AuthConfig;
import io.github.illuseahashmap.serviceregistry.domain.AuthMode;
import io.github.illuseahashmap.serviceregistry.domain.AuthType;
import io.github.illuseahashmap.serviceregistry.domain.Authorization;
import io.github.illuseahashmap.serviceregistry.domain.PublishedOperation;
import io.github.illuseahashmap.serviceregistry.domain.PublishedService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Content of a {@code <serviceKey>.service} resource: the service model as Flowable deploys it, with its base URL
 * and authorization. The authorization only names its credentials (secret, OAuth2 client, expression); resolved
 * credentials are never stored in a deployment.
 */
public final class ServiceModelJson {

    private static final AuthConfig NO_AUTH = new AuthConfig(AuthType.NONE, null, null);

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static String resourceName(String serviceKey) {
        return serviceKey + ServiceRepositoryService.RESOURCE_SUFFIX;
    }

    /** The model without resolved credentials; the authorization of the service and of each operation stays. */
    public static PublishedService modelOnly(PublishedService service) {
        List<PublishedOperation> operations = service.operations().stream()
                .map(operation -> operation.withCredentials(
                        operation.authMode() == null ? AuthMode.INHERIT : operation.authMode(), NO_AUTH))
                .toList();
        return new PublishedService(service.serviceKey(), service.name(), service.description(),
                blankToNull(service.baseUrl()), service.authorization(), operations);
    }

    /** What keeps this model from being deployed, empty when it can be. */
    public static List<String> problems(PublishedService service) {
        List<String> problems = new ArrayList<>();
        service.authorization().problems().forEach(problem -> problems.add("xác thực dịch vụ: " + problem));
        for (PublishedOperation operation : service.operations()) {
            if (operation.authMode() == AuthMode.OVERRIDE) {
                operation.authorization().problems().forEach(problem ->
                        problems.add("xác thực operation " + operation.operationKey() + ": " + problem));
            }
        }
        return problems;
    }

    /** Authorizations the model actually uses: the service's, and each operation's own override. */
    public static List<Authorization> authorizations(PublishedService service) {
        List<Authorization> authorizations = new ArrayList<>();
        authorizations.add(service.authorization());
        for (PublishedOperation operation : service.operations()) {
            if (operation.authMode() == AuthMode.OVERRIDE) {
                authorizations.add(operation.authorization());
            }
        }
        return authorizations;
    }

    /** Tenant variables read by the base URL and the credential expressions. */
    public static Set<String> tenantVariables(PublishedService service) {
        Set<String> names = new TreeSet<>(Authorization.tenantVariables(service.baseUrl()));
        authorizations(service).forEach(authorization -> names.addAll(authorization.tenantVariables()));
        return names;
    }

    public byte[] write(PublishedService service) {
        try {
            return objectMapper.writeValueAsBytes(modelOnly(service));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write service " + service.serviceKey(), exception);
        }
    }

    public PublishedService read(byte[] bytes) {
        try {
            return objectMapper.readValue(bytes, PublishedService.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read service definition", exception);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
