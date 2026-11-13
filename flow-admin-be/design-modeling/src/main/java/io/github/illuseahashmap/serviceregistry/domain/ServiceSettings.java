package io.github.illuseahashmap.serviceregistry.domain;

import java.util.Map;

/**
 * What a service needs from the environment it runs in: the base URL for relative operation paths and the
 * credentials. Never part of a deployment; the design of the service comes from the service engine.
 *
 * @param baseUrl    base URL for relative operation paths, null when every operation uses an absolute URL
 * @param sharedAuth credentials of operations that inherit them from the service
 * @param operations credentials of single operations, by operation key; an operation not listed uses the auth
 *                   mode of its deployed design with no own credentials
 */
public record ServiceSettings(String baseUrl, AuthConfig sharedAuth, Map<String, OperationAuth> operations) {

    private static final AuthConfig NO_AUTH = new AuthConfig(AuthType.NONE, null, null);

    public ServiceSettings {
        sharedAuth = sharedAuth == null ? NO_AUTH : sharedAuth;
        operations = operations == null ? Map.of() : Map.copyOf(operations);
    }

    /** Credentials of one operation. A null mode keeps the auth mode of the deployed design. */
    public record OperationAuth(AuthMode mode, AuthConfig auth) {

        public OperationAuth {
            auth = auth == null ? NO_AUTH : auth;
        }
    }
}
