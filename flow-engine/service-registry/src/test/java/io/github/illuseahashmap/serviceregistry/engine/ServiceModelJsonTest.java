package io.github.illuseahashmap.serviceregistry.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.illuseahashmap.serviceregistry.domain.ApiKeyLocation;
import io.github.illuseahashmap.serviceregistry.domain.AuthMode;
import io.github.illuseahashmap.serviceregistry.domain.AuthType;
import io.github.illuseahashmap.serviceregistry.domain.Authorization;
import io.github.illuseahashmap.serviceregistry.domain.CredentialSource;
import io.github.illuseahashmap.serviceregistry.domain.CredentialValue;
import io.github.illuseahashmap.serviceregistry.domain.HttpMethod;
import io.github.illuseahashmap.serviceregistry.domain.PublishedOperation;
import io.github.illuseahashmap.serviceregistry.domain.PublishedService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ServiceModelJsonTest {

    private final ServiceModelJson json = new ServiceModelJson();

    @Test
    void keepsTheModelAndItsReferencesButNeverResolvedCredentials() {
        PublishedOperation resolved = new PublishedOperation("call", HttpMethod.GET, "/a",
                AuthMode.INHERIT, AuthType.BEARER, null, "resolved-token", null, 1000, 1000,
                List.of(), List.of(), List.of(), List.of());
        PublishedService service = new PublishedService("crm", "CRM", null, "  ${tenant.crmBaseUrl}  ",
                bearer(CredentialSource.SECRET, "crmToken"), List.of(resolved));

        byte[] content = json.write(service);

        assertThat(new String(content, StandardCharsets.UTF_8)).doesNotContain("resolved-token");
        PublishedService read = json.read(content);
        assertThat(read.baseUrl()).isEqualTo("${tenant.crmBaseUrl}");
        assertThat(read.authorization().secrets()).containsExactly("crmToken");
        assertThat(read.operations().getFirst().authType()).isEqualTo(AuthType.NONE);
        assertThat(ServiceModelJson.resourceName("crm")).isEqualTo("crm.service");
    }

    @Test
    void collectsProblemsReferencesAndTenantVariablesOfTheUsedAuthorizations() {
        Authorization override = new Authorization(AuthType.API_KEY, null, ApiKeyLocation.QUERY, null,
                new CredentialValue(CredentialSource.EXPRESSION, "${tenant.auditKey}"), null, null, null, null);
        Authorization ignored = bearer(CredentialSource.VALUE, "not-used-because-inherit");
        PublishedService service = new PublishedService("crm", "CRM", null, "${tenant.crmBaseUrl}/v1",
                bearer(CredentialSource.OAUTH2_CLIENT, "crm-oauth"), List.of(
                operation("audit").withAuthorization(AuthMode.OVERRIDE, override),
                operation("read").withAuthorization(AuthMode.INHERIT, ignored)));

        assertThat(ServiceModelJson.problems(service)).singleElement().asString()
                .contains("operation audit").contains("header");
        assertThat(ServiceModelJson.authorizations(service)).hasSize(2);
        assertThat(ServiceModelJson.tenantVariables(service)).containsExactly("auditKey", "crmBaseUrl");
        assertThat(service.authorization().oauth2Clients()).containsExactly("crm-oauth");
    }

    @Test
    void checksTheFieldsOfEveryType() {
        CredentialValue secret = new CredentialValue(CredentialSource.SECRET, "s");
        assertThat(new Authorization(AuthType.MTLS, null, null, null, secret, null, null, null, null).problems())
                .containsExactly("certificatePem là bắt buộc");
        assertThat(new Authorization(AuthType.HMAC, "X-Sig", null, null,
                new CredentialValue(CredentialSource.EXPRESSION, "${k}"), null, null, null, null).problems())
                .containsExactly("key không được lấy từ EXPRESSION");
        assertThat(new Authorization(AuthType.AWS_SIG_V4, null, null, null, secret, null,
                new CredentialValue(CredentialSource.VALUE, "AKIA"), "us-east-1", null).problems())
                .containsExactly("awsService là bắt buộc");
        assertThat(new Authorization(AuthType.CUSTOM_HEADER, "X-T", null, null, null, null, null, null, null)
                .problems()).containsExactly("value là bắt buộc");
        assertThat(Authorization.NONE.problems()).isEmpty();
        assertThat(Authorization.NONE.secrets()).isEmpty();
        assertThat(Authorization.tenantVariables(null)).isEmpty();
        assertThat(Authorization.tenantVariables("${ tenant.a }-${tenant.b_2}")).isEqualTo(Set.of("a", "b_2"));
    }

    private static PublishedOperation operation(String key) {
        return new PublishedOperation(key, HttpMethod.GET, "/" + key, AuthMode.INHERIT, AuthType.NONE, null, null,
                null, 1000, 1000, List.of(), List.of(), List.of(), List.of());
    }

    private static Authorization bearer(CredentialSource source, String value) {
        return new Authorization(AuthType.BEARER, null, null, null, new CredentialValue(source, value),
                null, null, null, null);
    }
}
