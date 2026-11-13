package io.github.illuseahashmap.workflow.serviceregistry;

import io.github.illuseahashmap.serviceregistry.application.PublishedServiceInvoker;
import io.github.illuseahashmap.serviceregistry.application.ServiceEnvironment;
import io.github.illuseahashmap.serviceregistry.domain.HttpTransport;
import io.github.illuseahashmap.serviceregistry.domain.ServiceCaller;
import io.github.illuseahashmap.serviceregistry.domain.ServiceCatalog;
import io.github.illuseahashmap.serviceregistry.engine.JdbcServiceRepositoryService;
import io.github.illuseahashmap.serviceregistry.engine.ServiceRepositoryService;
import io.github.illuseahashmap.serviceregistry.infrastructure.JdbcServiceCatalog;
import io.github.illuseahashmap.serviceregistry.infrastructure.JdkHttpTransport;
import io.github.illuseahashmap.serviceregistry.infrastructure.ServiceSecretCipher;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flowable.common.engine.impl.EngineDeployer;
import org.flowable.spring.boot.ProcessEngineConfigurationConfigurer;
import org.springframework.beans.factory.annotation.Value;
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
    ServiceCatalog serviceCatalog(DataSource dataSource, ServiceSecretCipher serviceSecretCipher) {
        return new JdbcServiceCatalog(dataSource, serviceSecretCipher);
    }

    @Bean
    HttpTransport serviceHttpTransport() {
        return new JdkHttpTransport();
    }

    @Bean
    ServiceEnvironment serviceEnvironment(
            @Value("${workflow.services.environment:default}") String environment) {
        return () -> environment;
    }

    @Bean
    PublishedServiceInvoker publishedServiceInvoker(
            ServiceRepositoryService serviceRepositoryService,
            ServiceCatalog serviceCatalog,
            HttpTransport serviceHttpTransport,
            ServiceEnvironment serviceEnvironment) {
        return new PublishedServiceInvoker(serviceRepositoryService, serviceCatalog,
                new ServiceCaller(serviceHttpTransport), serviceEnvironment);
    }
}
