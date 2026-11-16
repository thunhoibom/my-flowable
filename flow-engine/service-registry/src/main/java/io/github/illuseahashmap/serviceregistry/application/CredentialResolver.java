package io.github.illuseahashmap.serviceregistry.application;

import io.github.illuseahashmap.serviceregistry.domain.AuthConfig;
import io.github.illuseahashmap.serviceregistry.domain.AuthDetails;
import io.github.illuseahashmap.serviceregistry.domain.AuthType;
import io.github.illuseahashmap.serviceregistry.domain.Authorization;
import io.github.illuseahashmap.serviceregistry.domain.CredentialValue;
import io.github.illuseahashmap.serviceregistry.domain.InvalidServiceRequestException;
import io.github.illuseahashmap.serviceregistry.domain.OauthClientCredentials;
import java.io.IOException;

/**
 * Turns the authorization of a model into the credentials of one call: a secret is read from this Flow Engine, an
 * OAuth2 client gives its access token, an expression is evaluated on the running process. Missing configuration
 * is an invalid request (a technical failure, retried like any job); an unreachable token endpoint is
 * SERVICE_UNAVAILABLE, like an unreachable service.
 */
public final class CredentialResolver {

    private static final AuthConfig NO_AUTH = new AuthConfig(AuthType.NONE, null, null);

    private final ServiceEnvironment environment;
    private final OauthClientCredentials oauth;

    public CredentialResolver(ServiceEnvironment environment, OauthClientCredentials oauth) {
        this.environment = environment;
        this.oauth = oauth;
    }

    public AuthConfig resolve(String tenantId, Authorization authorization, ExpressionEvaluator expressions,
                              int timeoutMillis) {
        Authorization auth = Authorization.orNone(authorization);
        Values values = new Values(tenantId, expressions, timeoutMillis);
        return switch (auth.type()) {
            case NONE -> NO_AUTH;
            case BASIC -> new AuthConfig(AuthType.BASIC, null,
                    values.text("username", auth.username()) + ":" + values.text("password", auth.value()));
            case BEARER -> new AuthConfig(AuthType.BEARER, null, values.text("token", auth.value()));
            case API_KEY -> new AuthConfig(AuthType.API_KEY, auth.headerName(), values.text("key", auth.value()),
                    auth.apiKeyLocation());
            case CUSTOM_HEADER -> new AuthConfig(AuthType.CUSTOM_HEADER, auth.headerName(),
                    values.text("value", auth.value()));
            case MTLS -> new AuthConfig(AuthType.MTLS, null, values.text("privateKey", auth.value()), null,
                    new AuthDetails(auth.certificatePem(), null, null, null, null));
            case HMAC -> new AuthConfig(AuthType.HMAC, null, values.text("key", auth.value()), null,
                    new AuthDetails(null, null, null, null, auth.headerName()));
            case AWS_SIG_V4 -> new AuthConfig(AuthType.AWS_SIG_V4, null, values.text("secretKey", auth.value()), null,
                    new AuthDetails(null, values.text("accessKey", auth.accessKey()), auth.region(),
                            auth.awsService(), null));
        };
    }

    private final class Values {

        private final String tenantId;
        private final ExpressionEvaluator expressions;
        private final int timeoutMillis;

        Values(String tenantId, ExpressionEvaluator expressions, int timeoutMillis) {
            this.tenantId = tenantId;
            this.expressions = expressions == null ? ExpressionEvaluator.NONE : expressions;
            this.timeoutMillis = timeoutMillis;
        }

        String text(String field, CredentialValue credential) {
            if (credential == null || credential.missing()) {
                throw new InvalidServiceRequestException(field + " is required");
            }
            String name = credential.value().trim();
            String value = switch (credential.source()) {
                case VALUE -> credential.value();
                case EXPRESSION -> expressions.evaluate(name);
                case SECRET -> environment.secret(tenantId, name).orElseThrow(
                        () -> new InvalidServiceRequestException("Secret " + name + " does not exist"));
                case OAUTH2_CLIENT -> token(name);
            };
            if (value == null || value.isEmpty()) {
                throw new InvalidServiceRequestException(field + " is empty");
            }
            return value;
        }

        private String token(String registrationKey) {
            OauthClientCredentials.Client client = environment.oauth2Client(tenantId, registrationKey).orElseThrow(
                    () -> new InvalidServiceRequestException("OAuth2 client " + registrationKey + " does not exist"));
            try {
                return oauth.accessToken(client, timeoutMillis);
            } catch (IOException exception) {
                throw new ServiceCallException("SERVICE_UNAVAILABLE");
            }
        }
    }
}
