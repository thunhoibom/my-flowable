package io.github.illuseahashmap.serviceregistry.engine;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.serviceregistry.domain.AuthConfig;
import io.github.illuseahashmap.serviceregistry.domain.AuthMode;
import io.github.illuseahashmap.serviceregistry.domain.AuthType;
import io.github.illuseahashmap.serviceregistry.domain.PublishedOperation;
import io.github.illuseahashmap.serviceregistry.domain.PublishedService;
import java.io.IOException;
import java.util.List;

/**
 * Content of a {@code <serviceKey>.service} resource. Only the design travels with a deployment:
 * operations, parameters, body, error rules, and steps. Base URLs and credentials are environment
 * settings, read at call time, so they are removed here and never stored in a deployment.
 */
public final class ServiceModelJson {

    private static final AuthConfig NO_AUTH = new AuthConfig(AuthType.NONE, null, null);

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static String resourceName(String serviceKey) {
        return serviceKey + ServiceRepositoryService.RESOURCE_SUFFIX;
    }

    /**
     * Design only. Same service without base URLs, shared auth, or operation credentials.
     * The auth mode stays: whether an operation inherits, overrides, or sends nothing is part of the design.
     */
    public static PublishedService designOnly(PublishedService service) {
        List<PublishedOperation> operations = service.operations().stream()
                .map(operation -> operation.withCredentials(
                        operation.authMode() == null ? AuthMode.OVERRIDE : operation.authMode(), NO_AUTH))
                .toList();
        return new PublishedService(service.serviceKey(), service.name(), service.description(),
                List.of(), NO_AUTH, operations);
    }

    public byte[] write(PublishedService service) {
        try {
            return objectMapper.writeValueAsBytes(designOnly(service));
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
}
