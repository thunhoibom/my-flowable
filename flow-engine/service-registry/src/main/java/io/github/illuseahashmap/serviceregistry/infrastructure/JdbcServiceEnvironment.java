package io.github.illuseahashmap.serviceregistry.infrastructure;

import io.github.illuseahashmap.serviceregistry.application.ServiceEnvironment;
import io.github.illuseahashmap.serviceregistry.domain.OauthClientCredentials;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/** {@link ServiceEnvironment} over {@code work_secret}, {@code work_oauth2_client} and {@code work_tenant_variable}. */
public final class JdbcServiceEnvironment implements ServiceEnvironment {

    private final JdbcSecretStore secrets;
    private final JdbcOauth2ClientStore clients;
    private final JdbcTenantVariableStore variables;

    public JdbcServiceEnvironment(JdbcSecretStore secrets, JdbcOauth2ClientStore clients,
                                  JdbcTenantVariableStore variables) {
        this.secrets = secrets;
        this.clients = clients;
        this.variables = variables;
    }

    @Override
    public Optional<String> secret(String tenantId, String name) {
        return secrets.value(tenantId, name);
    }

    @Override
    public Optional<OauthClientCredentials.Client> oauth2Client(String tenantId, String registrationKey) {
        return clients.registration(tenantId, registrationKey).map(registration ->
                new OauthClientCredentials.Client(registration.tokenUri(), registration.clientId(),
                        registration.clientSecret(), registration.scopes()));
    }

    @Override
    public Set<String> missingSecrets(String tenantId, Collection<String> names) {
        return secrets.missing(tenantId, names);
    }

    @Override
    public Set<String> missingOauth2Clients(String tenantId, Collection<String> registrationKeys) {
        return clients.missing(tenantId, registrationKeys);
    }

    @Override
    public Set<String> missingTenantVariables(String tenantId, Collection<String> names) {
        return variables.missing(tenantId, names);
    }
}
