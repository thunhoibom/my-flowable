package io.github.illuseahashmap.serviceregistry.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AuthInheritanceTest {

    @Test
    void selectsServiceOperationOrNoAuthorization() {
        Authorization service = new Authorization(AuthType.BEARER, null, null, null,
                new CredentialValue(CredentialSource.SECRET, "crmToken"), null, null, null, null);
        Authorization own = new Authorization(AuthType.API_KEY, "X-Api-Key", null, null,
                new CredentialValue(CredentialSource.SECRET, "opKey"), null, null, null, null);
        assertEquals(service, AuthInheritance.resolve(AuthMode.INHERIT, service, own));
        assertEquals(service, AuthInheritance.resolve(null, service, own));
        assertEquals(own, AuthInheritance.resolve(AuthMode.OVERRIDE, service, own));
        // NONE drops the service authorization for a public operation.
        assertEquals(Authorization.NONE, AuthInheritance.resolve(AuthMode.NONE, service, own));
    }

    @Test
    void secretValuesCanNeverBeWrittenInTheModel() {
        Authorization literalToken = new Authorization(AuthType.BEARER, null, null, null,
                new CredentialValue(CredentialSource.VALUE, "abc"), null, null, null, null);
        assertEquals(1, literalToken.problems().size());
        Authorization header = new Authorization(AuthType.CUSTOM_HEADER, "X-Tenant", null, null,
                new CredentialValue(CredentialSource.VALUE, "abc"), null, null, null, null);
        assertEquals(0, header.problems().size());
        Authorization noHeader = new Authorization(AuthType.API_KEY, null, null, null,
                new CredentialValue(CredentialSource.SECRET, "k"), null, null, null, null);
        assertEquals(1, noHeader.problems().size());
        Authorization basic = new Authorization(AuthType.BASIC, null, null,
                new CredentialValue(CredentialSource.VALUE, "svc"),
                new CredentialValue(CredentialSource.EXPRESSION, "${tenant.crmPassword}"), null, null, null, null);
        assertEquals(0, basic.problems().size());
        assertEquals(java.util.Set.of("crmPassword"), basic.tenantVariables());
    }
}
