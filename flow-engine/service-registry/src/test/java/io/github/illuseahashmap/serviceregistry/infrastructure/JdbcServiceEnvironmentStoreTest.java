package io.github.illuseahashmap.serviceregistry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.illuseahashmap.serviceregistry.domain.ApiKeyLocation;
import io.github.illuseahashmap.serviceregistry.domain.AuthConfig;
import io.github.illuseahashmap.serviceregistry.domain.AuthDetails;
import io.github.illuseahashmap.serviceregistry.domain.AuthMode;
import io.github.illuseahashmap.serviceregistry.domain.AuthType;
import io.github.illuseahashmap.serviceregistry.domain.ServiceSettings;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcServiceEnvironmentStoreTest {

    private static final String KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static JdbcServiceEnvironmentStore store;

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            String sql = new String(JdbcServiceEnvironmentStoreTest.class
                    .getResourceAsStream("/db/migration/platform/V42__work_service_environment.sql").readAllBytes(),
                    StandardCharsets.UTF_8);
            for (String command : sql.split(";")) {
                if (!command.isBlank()) {
                    statement.execute(command);
                }
            }
        }
        store = new JdbcServiceEnvironmentStore(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()), new ServiceSecretCipher(KEY));
    }

    @Test
    void storesSecretsOnlyAsCipherAndReadsThemBack() throws Exception {
        store.save("t1", "crm", settings("https://crm.example", "shared-token", "op-key", "-----PEM-----"), "ops");

        ServiceSettings found = store.find("t1", "crm").orElseThrow();
        assertThat(found.baseUrl()).isEqualTo("https://crm.example");
        assertThat(found.sharedAuth().secret()).isEqualTo("shared-token");
        assertThat(found.sharedAuth().details().certificatePem()).isEqualTo("-----PEM-----");
        assertThat(found.sharedAuth().details().clientId()).isEqualTo("client");
        ServiceSettings.OperationAuth audit = found.operations().get("audit");
        assertThat(audit.mode()).isEqualTo(AuthMode.OVERRIDE);
        assertThat(audit.auth().type()).isEqualTo(AuthType.API_KEY);
        assertThat(audit.auth().apiKeyLocation()).isEqualTo(ApiKeyLocation.QUERY);
        assertThat(audit.auth().secret()).isEqualTo("op-key");

        String row = rawRow("t1", "crm");
        assertThat(row).doesNotContain("shared-token").doesNotContain("op-key").doesNotContain("PEM");
    }

    @Test
    void aSaveWithoutSecretsKeepsTheStoredOnes() {
        store.save("t2", "crm", settings("https://old.example", "shared-token", "op-key", null), "ops");

        store.save("t2", "crm", settings("https://new.example", null, null, null), "ops2");

        ServiceSettings found = store.find("t2", "crm").orElseThrow();
        assertThat(found.baseUrl()).isEqualTo("https://new.example");
        assertThat(found.sharedAuth().secret()).isEqualTo("shared-token");
        assertThat(found.operations().get("audit").auth().secret()).isEqualTo("op-key");
        JdbcServiceEnvironmentStore.Entry entry = store.entry("t2", "crm").orElseThrow();
        assertThat(entry.updatedBy()).isEqualTo("ops2");
        assertThat(entry.settings().sharedAuth().secret()).isNull();
        assertThat(entry.settings().operations().get("audit").auth().secret()).isNull();
        assertThat(entry.storedSecrets()).containsExactlyInAnyOrder("shared.secret", "operations.audit.secret");
    }

    @Test
    void isScopedByTenantAndCanBeDeleted() {
        store.save("t3", "erp", new ServiceSettings("https://erp.example", null, Map.of()), "ops");

        assertThat(store.find("other", "erp")).isEmpty();
        assertThat(store.list("t3")).extracting(JdbcServiceEnvironmentStore.Entry::serviceKey).containsExactly("erp");
        assertThat(store.entry("t3", "erp").orElseThrow().storedSecrets()).isEmpty();

        assertThat(store.delete("t3", "erp")).isTrue();
        assertThat(store.find("t3", "erp")).isEmpty();
        assertThat(store.delete("t3", "erp")).isFalse();
    }

    private static ServiceSettings settings(String baseUrl, String sharedSecret, String operationSecret,
                                            String certificatePem) {
        AuthDetails details = new AuthDetails("https://idp.example/token", "client", List.of("read"),
                certificatePem, null, null, null, null, null);
        return new ServiceSettings(baseUrl,
                new AuthConfig(AuthType.BEARER, null, sharedSecret, ApiKeyLocation.HEADER, details),
                Map.of("audit", new ServiceSettings.OperationAuth(AuthMode.OVERRIDE,
                        new AuthConfig(AuthType.API_KEY, "key", operationSecret, ApiKeyLocation.QUERY))));
    }

    private static String rawRow(String tenantId, String serviceKey) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT auth_config || coalesce(secret_cipher, '') "
                     + "FROM work_service_environment WHERE tenant_id = '" + tenantId
                     + "' AND service_key = '" + serviceKey + "'")) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
