package io.github.illuseahashmap.serviceregistry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.illuseahashmap.serviceregistry.domain.ApiKeyLocation;
import io.github.illuseahashmap.serviceregistry.domain.AuthConfig;
import io.github.illuseahashmap.serviceregistry.domain.AuthType;
import io.github.illuseahashmap.serviceregistry.domain.Authorization;
import io.github.illuseahashmap.serviceregistry.domain.CredentialSource;
import io.github.illuseahashmap.serviceregistry.domain.CredentialValue;
import io.github.illuseahashmap.serviceregistry.domain.InvalidServiceRequestException;
import io.github.illuseahashmap.serviceregistry.domain.OauthClientCredentials;
import io.github.illuseahashmap.serviceregistry.domain.TransportResponse;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CredentialResolverTest {

    private static final Map<String, String> SECRETS = Map.of("key", "secret-key", "pem", "private-pem");

    private final CredentialResolver resolver = new CredentialResolver(new ServiceEnvironment() {
        @Override
        public Optional<String> secret(String tenantId, String name) {
            return Optional.ofNullable(SECRETS.get(name));
        }

        @Override
        public Optional<OauthClientCredentials.Client> oauth2Client(String tenantId, String registrationKey) {
            return "down".equals(registrationKey)
                    ? Optional.of(new OauthClientCredentials.Client("https://idp/token", "c", "s", List.of()))
                    : Optional.empty();
        }

        @Override
        public Set<String> missingSecrets(String tenantId, Collection<String> names) {
            return Set.of();
        }

        @Override
        public Set<String> missingOauth2Clients(String tenantId, Collection<String> registrationKeys) {
            return Set.of();
        }

        @Override
        public Set<String> missingTenantVariables(String tenantId, Collection<String> names) {
            return Set.of();
        }
    }, new OauthClientCredentials(request -> new TransportResponse(503, ""), Clock.systemUTC()));

    @Test
    void resolvesEveryTypeIntoTheCredentialsOfOneCall() {
        assertThat(resolve(Authorization.NONE).type()).isEqualTo(AuthType.NONE);
        assertThat(resolve(null).type()).isEqualTo(AuthType.NONE);

        AuthConfig apiKey = resolve(new Authorization(AuthType.API_KEY, "api_key", ApiKeyLocation.QUERY, null,
                secret("key"), null, null, null, null));
        assertThat(apiKey.apiKeyHeader()).isEqualTo("api_key");
        assertThat(apiKey.apiKeyLocation()).isEqualTo(ApiKeyLocation.QUERY);
        assertThat(apiKey.secret()).isEqualTo("secret-key");

        AuthConfig mtls = resolve(new Authorization(AuthType.MTLS, null, null, null, secret("pem"),
                "-----CERT-----", null, null, null));
        assertThat(mtls.secret()).isEqualTo("private-pem");
        assertThat(mtls.details().certificatePem()).isEqualTo("-----CERT-----");

        AuthConfig hmac = resolve(new Authorization(AuthType.HMAC, "X-Signature", null, null, secret("key"),
                null, null, null, null));
        assertThat(hmac.details().signatureHeader()).isEqualTo("X-Signature");

        AuthConfig aws = resolve(new Authorization(AuthType.AWS_SIG_V4, null, null, null, secret("key"), null,
                new CredentialValue(CredentialSource.EXPRESSION, "${accessKey}"), "us-east-1", "s3"));
        assertThat(aws.details().accessKey()).isEqualTo("evaluated:${accessKey}");
        assertThat(aws.details().region()).isEqualTo("us-east-1");
        assertThat(aws.details().awsService()).isEqualTo("s3");
        assertThat(aws.secret()).isEqualTo("secret-key");
    }

    @Test
    void missingOrEmptyCredentialsAreInvalidAndAnUnreachableTokenEndpointIsUnavailable() {
        assertThatThrownBy(() -> resolve(bearer(new CredentialValue(CredentialSource.SECRET, "nope"))))
                .isInstanceOf(InvalidServiceRequestException.class).hasMessageContaining("Secret nope");
        assertThatThrownBy(() -> resolve(bearer(new CredentialValue(CredentialSource.OAUTH2_CLIENT, "nope"))))
                .isInstanceOf(InvalidServiceRequestException.class).hasMessageContaining("OAuth2 client nope");
        assertThatThrownBy(() -> resolve(bearer(null)))
                .isInstanceOf(InvalidServiceRequestException.class).hasMessageContaining("token is required");
        assertThatThrownBy(() -> resolver.resolve("t1", bearer(new CredentialValue(CredentialSource.EXPRESSION,
                "${empty}")), expression -> "", 1000))
                .isInstanceOf(InvalidServiceRequestException.class).hasMessageContaining("token is empty");
        assertThatThrownBy(() -> resolver.resolve("t1", bearer(new CredentialValue(CredentialSource.EXPRESSION,
                "${x}")), null, 1000))
                .isInstanceOf(InvalidServiceRequestException.class).hasMessageContaining("running process");
        assertThatThrownBy(() -> resolve(bearer(new CredentialValue(CredentialSource.OAUTH2_CLIENT, "down"))))
                .isInstanceOfSatisfying(ServiceCallException.class,
                        error -> assertThat(error.bpmnErrorCode()).isEqualTo("SERVICE_UNAVAILABLE"));
    }

    @Test
    void anExpressionEvaluatorLeavesPlainTextAlone() {
        ExpressionEvaluator upper = String::toUpperCase;
        assertThat(upper.text("https://crm.example")).isEqualTo("https://crm.example");
        assertThat(upper.text("${tenant.x}")).isEqualTo("${TENANT.X}");
        assertThat(upper.text(null)).isNull();
        assertThat(RestSettings.NONE.baseUrl("crm")).isEmpty();
        assertThat(new RestSettings(Map.of("crm", new RestSettings.Setting(" ", 0))).timeout("crm")).isEmpty();
    }

    private AuthConfig resolve(Authorization authorization) {
        return resolver.resolve("t1", authorization, expression -> "evaluated:" + expression, 1000);
    }

    private static CredentialValue secret(String name) {
        return new CredentialValue(CredentialSource.SECRET, name);
    }

    private static Authorization bearer(CredentialValue value) {
        return new Authorization(AuthType.BEARER, null, null, null, value, null, null, null, null);
    }
}
