package io.github.illuseahashmap.designhub.config;

import io.github.illuseahashmap.designhub.engine.FlowEngineClient;
import io.github.illuseahashmap.designhub.engine.FlowEngineProperties;
import io.github.illuseahashmap.serviceregistry.domain.ServiceCatalog;
import io.github.illuseahashmap.serviceregistry.infrastructure.JdbcServiceCatalog;
import io.github.illuseahashmap.serviceregistry.infrastructure.ServiceSecretCipher;
import io.github.illuseahashmap.workflow.config.EventRegistryProperties;
import io.github.illuseahashmap.workflow.config.ProcessDefinitionProperties;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ProcessDefinitionProperties.class, EventRegistryProperties.class})
public class AdminBeConfiguration {

    @Bean
    FlowEngineProperties flowEngineProperties(
            @Value("${flow-admin.engine.base-url:}") String baseUrl,
            @Value("${flow-admin.engine.client-code:flow-admin-be}") String clientCode,
            @Value("${flow-admin.engine.client-secret:}") String clientSecret,
            @Value("${flow-admin.engine.token-version:1}") int tokenVersion,
            @Value("${flow-admin.engine.environment:default}") String environment,
            @Value("${flow-admin.engine.connect-timeout:5s}") Duration connectTimeout,
            @Value("${flow-admin.engine.read-timeout:30s}") Duration readTimeout,
            @Value("${flow-admin.auth.cache-ttl:60s}") Duration authCacheTtl) {
        return new FlowEngineProperties(baseUrl, clientCode, clientSecret, tokenVersion, environment,
                connectTimeout, readTimeout, authCacheTtl);
    }

    @Bean
    FlowEngineClient flowEngineClient(FlowEngineProperties properties) {
        return new FlowEngineClient(properties);
    }

    /** Same key as Flow Engine used before the split: the catalog rows were encrypted with it. */
    @Bean
    ServiceSecretCipher serviceSecretCipher(
            @Value("${flow-admin.service-secrets.master-key-base64:}") String masterKeyBase64) {
        return new ServiceSecretCipher(masterKeyBase64);
    }

    @Bean
    ServiceCatalog serviceCatalog(DataSource dataSource, ServiceSecretCipher serviceSecretCipher) {
        return new JdbcServiceCatalog(dataSource, serviceSecretCipher);
    }
}
