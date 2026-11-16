package io.github.illuseahashmap.serviceregistry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ServiceConfigStoresTest {

    private static final String KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static JdbcSecretStore secrets;
    private static JdbcOauth2ClientStore clients;
    private static JdbcTenantVariableStore variables;

    @BeforeAll
    static void createTables() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            for (String script : List.of("V44__work_secret.sql", "V45__work_oauth2_client.sql",
                    "V46__work_tenant_variable.sql")) {
                statement.execute(new String(ServiceConfigStoresTest.class.getResourceAsStream("/db/" + script)
                        .readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ServiceSecretCipher cipher = new ServiceSecretCipher(KEY);
        secrets = new JdbcSecretStore(dataSource, cipher);
        clients = new JdbcOauth2ClientStore(dataSource, cipher);
        variables = new JdbcTenantVariableStore(dataSource, cipher);
    }

    @Test
    void secretIsWriteOnceEncryptedAndTenantScoped() throws Exception {
        assertThat(secrets.create("t1", "crmToken", "s3cret-value", "ops")).isTrue();
        assertThat(secrets.create("t1", "crmToken", "other", "ops")).isFalse();

        assertThat(secrets.value("t1", "crmToken")).contains("s3cret-value");
        assertThat(secrets.value("t2", "crmToken")).isEmpty();
        assertThat(secrets.list("t1")).extracting(JdbcSecretStore.Entry::name).containsExactly("crmToken");
        assertThat(secrets.missing("t1", Set.of("crmToken", "nope"))).containsExactly("nope");
        assertThat(raw("SELECT value_cipher FROM work_secret WHERE name = 'crmToken'"))
                .doesNotContain("s3cret-value");

        assertThat(secrets.delete("t1", "crmToken")).isTrue();
        assertThat(secrets.exists("t1", "crmToken")).isFalse();
    }

    @Test
    void oauth2ClientKeepsItsSecretWhenSavedWithoutOne() throws Exception {
        assertThatThrownBy(() -> clients.save("t1", "crm-oauth", new JdbcOauth2ClientStore.Input(
                "crm", null, "https://idp/token", List.of(), null), "ops"))
                .isInstanceOf(IllegalArgumentException.class);

        clients.save("t1", "crm-oauth", new JdbcOauth2ClientStore.Input(
                "crm", "client-secret-1", "https://idp/token", List.of("read", "write"), null), "ops");
        clients.save("t1", "crm-oauth", new JdbcOauth2ClientStore.Input(
                "crm2", null, "https://idp/token2", List.of("read"), null), "ops2");

        JdbcOauth2ClientStore.Registration registration = clients.registration("t1", "crm-oauth").orElseThrow();
        assertThat(registration.clientId()).isEqualTo("crm2");
        assertThat(registration.clientSecret()).isEqualTo("client-secret-1");
        assertThat(registration.tokenUri()).isEqualTo("https://idp/token2");
        assertThat(registration.scopes()).containsExactly("read");
        assertThat(clients.find("t1", "crm-oauth").orElseThrow().grantType())
                .isEqualTo(JdbcOauth2ClientStore.CLIENT_CREDENTIALS);
        assertThat(clients.missing("t1", Set.of("crm-oauth", "x"))).containsExactly("x");
        assertThat(raw("SELECT client_secret_cipher FROM work_oauth2_client")).doesNotContain("client-secret-1");
    }

    @Test
    void protectedVariableIsMaskedEncryptedAndStaysProtected() throws Exception {
        variables.save("t1", "crmBaseUrl", "https://crm.dev", false, "ops");
        variables.save("t1", "crmPassword", "p@ss", true, "ops");

        assertThat(variables.value("t1", "crmBaseUrl")).contains("https://crm.dev");
        assertThat(variables.value("t1", "crmPassword")).contains("p@ss");
        assertThat(variables.find("t1", "crmPassword").orElseThrow().value()).isEqualTo(JdbcTenantVariableStore.MASK);
        assertThat(raw("SELECT value_cipher FROM work_tenant_variable WHERE name = 'crmPassword'"))
                .doesNotContain("p@ss");

        variables.save("t1", "crmPassword", null, true, "ops");
        assertThat(variables.value("t1", "crmPassword")).contains("p@ss");
        assertThatThrownBy(() -> variables.save("t1", "crmPassword", "plain", false, "ops"))
                .isInstanceOf(IllegalArgumentException.class);

        variables.save("t1", "crmBaseUrl", "https://crm.prod", true, "ops");
        assertThat(variables.value("t1", "crmBaseUrl")).contains("https://crm.prod");
        assertThat(variables.missing("t1", Set.of("crmBaseUrl", "other"))).containsExactly("other");
        assertThat(variables.value("t2", "crmBaseUrl")).isEmpty();
    }

    @Test
    void serviceEnvironmentReadsAllThreeStoresOfTheTenant() {
        secrets.create("t9", "envToken", "tok", "ops");
        clients.save("t9", "env-oauth", new JdbcOauth2ClientStore.Input(
                "env", "env-secret", "https://idp/token", List.of("a"), null), "ops");
        variables.save("t9", "envUrl", "https://env.example", false, "ops");
        JdbcServiceEnvironment environment = new JdbcServiceEnvironment(secrets, clients, variables);

        assertThat(environment.secret("t9", "envToken")).contains("tok");
        assertThat(environment.oauth2Client("t9", "env-oauth")).hasValueSatisfying(client -> {
            assertThat(client.tokenUrl()).isEqualTo("https://idp/token");
            assertThat(client.clientSecret()).isEqualTo("env-secret");
            assertThat(client.scopes()).containsExactly("a");
        });
        assertThat(environment.oauth2Client("t1", "env-oauth")).isEmpty();
        assertThat(environment.missingSecrets("t9", Set.of("envToken", "x"))).containsExactly("x");
        assertThat(environment.missingOauth2Clients("t9", Set.of("env-oauth", "y"))).containsExactly("y");
        assertThat(environment.missingTenantVariables("t9", Set.of("envUrl", "z"))).containsExactly("z");
        assertThat(environment.missingSecrets("t9", Set.of())).isEmpty();
    }

    private static String raw(String sql) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            StringBuilder values = new StringBuilder();
            while (resultSet.next()) {
                values.append(resultSet.getString(1)).append('\n');
            }
            return values.toString();
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
