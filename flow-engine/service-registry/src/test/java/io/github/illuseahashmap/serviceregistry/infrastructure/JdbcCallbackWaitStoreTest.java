package io.github.illuseahashmap.serviceregistry.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.illuseahashmap.serviceregistry.domain.CallbackWait;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcCallbackWaitStoreTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            for (String script : new String[] {"V32__add_service_registry", "V33__add_service_operation_parameters",
                    "V34__service_catalog_foundation", "V35__service_http_content", "V36__service_extended_auth",
                    "V37__service_multi_step", "V41__service_engine"}) {
                String sql = new String(JdbcCallbackWaitStoreTest.class.getResourceAsStream(
                        "/db/" + script + ".sql").readAllBytes(), StandardCharsets.UTF_8);
                for (String command : sql.split(";")) {
                    if (!command.isBlank()) {
                        statement.execute(command);
                    }
                }
            }
        }
    }

    @Test
    void takesAWaitOnceAndExpiresOldOnes() {
        var waits = new JdbcCallbackWaitStore(new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())));
        waits.insert(new CallbackWait("corr-1", "exec-1", "pages", "list", "{\"id\":\"leadId\"}", 50L, "tenant-a",
                "deployment-1"));
        var taken = waits.take("corr-1").orElseThrow();
        assertEquals("exec-1", taken.executionId());
        assertEquals("tenant-a", taken.tenantId());
        assertEquals("deployment-1", taken.parentDeploymentId());
        assertTrue(waits.take("corr-1").isEmpty());
        waits.insert(new CallbackWait("corr-2", "exec-2", "pages", "list", "{}", 1L, null, null));
        assertEquals("corr-2", waits.takeExpired(10L).getFirst().correlationId());
        assertTrue(waits.takeExpired(10L).isEmpty());
    }
}
