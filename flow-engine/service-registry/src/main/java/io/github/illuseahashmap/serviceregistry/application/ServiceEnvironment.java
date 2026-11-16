package io.github.illuseahashmap.serviceregistry.application;

import io.github.illuseahashmap.serviceregistry.domain.OauthClientCredentials;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * The values of this Flow Engine that service models reference by name: secrets, OAuth2 clients and tenant
 * variables (Flowable Hub "Secrets", "OAuth2 Clients", "Tenant Variables").
 */
public interface ServiceEnvironment {

    Optional<String> secret(String tenantId, String name);

    Optional<OauthClientCredentials.Client> oauth2Client(String tenantId, String registrationKey);

    Set<String> missingSecrets(String tenantId, Collection<String> names);

    Set<String> missingOauth2Clients(String tenantId, Collection<String> registrationKeys);

    Set<String> missingTenantVariables(String tenantId, Collection<String> names);
}
