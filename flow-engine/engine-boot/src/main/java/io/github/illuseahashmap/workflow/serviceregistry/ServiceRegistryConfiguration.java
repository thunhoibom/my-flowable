package io.github.illuseahashmap.workflow.serviceregistry;

import io.github.illuseahashmap.serviceregistry.application.PublishedServiceInvoker;
import io.github.illuseahashmap.serviceregistry.application.CredentialResolver;
import io.github.illuseahashmap.serviceregistry.application.ServiceEnvironment;
import io.github.illuseahashmap.serviceregistry.domain.HttpTransport;
import io.github.illuseahashmap.serviceregistry.domain.ServiceCaller;
import io.github.illuseahashmap.serviceregistry.engine.JdbcServiceRepositoryService;
import io.github.illuseahashmap.serviceregistry.engine.ServiceRepositoryService;
import io.github.illuseahashmap.serviceregistry.domain.OauthClientCredentials;
import io.github.illuseahashmap.serviceregistry.infrastructure.JdbcServiceEnvironment;
import io.github.illuseahashmap.serviceregistry.infrastructure.JdbcOauth2ClientStore;
import io.github.illuseahashmap.serviceregistry.infrastructure.JdbcSecretStore;
import io.github.illuseahashmap.serviceregistry.infrastructure.JdbcTenantVariableStore;
import io.github.illuseahashmap.serviceregistry.infrastructure.JdkHttpTransport;
import io.github.illuseahashmap.serviceregistry.infrastructure.ServiceSecretCipher;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flowable.common.engine.impl.EngineDeployer;
import org.flowable.common.engine.impl.javax.el.ELResolver;
import org.flowable.spring.boot.ProcessEngineConfigurationConfigurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class ServiceRegistryConfiguration {

    @Bean
    ServiceRepositoryService serviceRepositoryService(
            DataSource dataSource,
            @Value("${workflow.service-registry.definition-cache-limit:200}") int definitionCacheLimit,
            @Value("${workflow.service-registry.always-lookup-latest-definition-version:false}")
            boolean alwaysLookupLatestDefinitionVersion,
            @Value("${workflow.service-registry.fallback-default-tenant:}") String fallbackDefaultTenant) {
        return new JdbcServiceRepositoryService(dataSource, definitionCacheLimit, alwaysLookupLatestDefinitionVersion,
                StringUtils.hasText(fallbackDefaultTenant) ? fallbackDefaultTenant : null);
    }

    /** Registers the service deployer on the process engine, like Flowable's event registry configurator. */
    @Bean
    ProcessEngineConfigurationConfigurer serviceDeployerConfigurer(ServiceRepositoryService serviceRepositoryService) {
        return configuration -> {
            List<EngineDeployer> deployers = configuration.getCustomPostDeployers() == null
                    ? new ArrayList<>() : new ArrayList<>(configuration.getCustomPostDeployers());
            deployers.add(new ServiceDeployer(serviceRepositoryService));
            configuration.setCustomPostDeployers(deployers);
        };
    }

    @Bean
    ServiceSecretCipher serviceSecretCipher(
            @Value("${workflow.service.secrets.master-key-base64:}") String masterKeyBase64) {
        return new ServiceSecretCipher(masterKeyBase64);
    }

    @Bean
    HttpTransport serviceHttpTransport() {
        return new JdkHttpTransport();
    }

    /** Secrets, OAuth2 clients and tenant variables: the environment values that service models reference. */
    @Bean
    JdbcSecretStore secretStore(DataSource dataSource, ServiceSecretCipher serviceSecretCipher) {
        return new JdbcSecretStore(dataSource, serviceSecretCipher);
    }

    @Bean
    JdbcOauth2ClientStore oauth2ClientStore(DataSource dataSource, ServiceSecretCipher serviceSecretCipher) {
        return new JdbcOauth2ClientStore(dataSource, serviceSecretCipher);
    }

    @Bean
    JdbcTenantVariableStore tenantVariableStore(DataSource dataSource, ServiceSecretCipher serviceSecretCipher) {
        return new JdbcTenantVariableStore(dataSource, serviceSecretCipher);
    }

    @Bean
    ServiceEnvironment serviceEnvironment(JdbcSecretStore secretStore, JdbcOauth2ClientStore oauth2ClientStore,
                                          JdbcTenantVariableStore tenantVariableStore) {
        return new JdbcServiceEnvironment(secretStore, oauth2ClientStore, tenantVariableStore);
    }

    /** {@code ${tenant.<name>}} in Flowable expressions (input mappings, service base URLs, credentials). */
    @Bean
    ProcessEngineConfigurationConfigurer tenantVariableResolverConfigurer(JdbcTenantVariableStore tenantVariableStore) {
        return configuration -> {
            List<ELResolver> resolvers = configuration.getPreDefaultELResolvers() == null
                    ? new ArrayList<>() : new ArrayList<>(configuration.getPreDefaultELResolvers());
            resolvers.add(new TenantVariableELResolver(tenantVariableStore));
            configuration.setPreDefaultELResolvers(resolvers);
        };
    }

    /** {@code workflow.service-registry.rest-settings.<serviceKey | default>.base-url | timeout}. */
    @Bean
    @ConfigurationProperties("workflow.service-registry")
    ServiceRegistryProperties serviceRegistryProperties() {
        return new ServiceRegistryProperties();
    }

    @Bean
    PublishedServiceInvoker publishedServiceInvoker(ServiceRepositoryService serviceRepositoryService,
                                                    ServiceEnvironment serviceEnvironment,
                                                    ServiceRegistryProperties serviceRegistryProperties,
                                                    HttpTransport serviceHttpTransport) {
        return new PublishedServiceInvoker(serviceRepositoryService,
                new CredentialResolver(serviceEnvironment,
                        new OauthClientCredentials(serviceHttpTransport, Clock.systemUTC())),
                serviceRegistryProperties.restSettings(), new ServiceCaller(serviceHttpTransport));
    }
}
