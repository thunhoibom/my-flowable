package io.github.illuseahashmap.workflow.process.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.illuseahashmap.workflow.process.domain.CallReference;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcCallReferenceRepositoryTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            String sql = new String(JdbcCallReferenceRepositoryTest.class.getResourceAsStream(
                    "/db/V39__workflow_call_reference.sql").readAllBytes(), StandardCharsets.UTF_8);
            for (String command : sql.split(";")) {
                if (!command.isBlank()) {
                    statement.execute(command);
                }
            }
        }
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @Test
    void replacesAndQueriesCallReferences() {
        var references = new JdbcCallReferenceRepository(jdbc);
        references.replaceForDefinition("t1", "order:1:1", List.of(
                new CallReference("order:1:1", "order", 1, "pay", "payment"),
                new CallReference("order:1:1", "order", 1, "ship", "shipping")));
        references.replaceForDefinition("t1", "refund:1:3", List.of(
                new CallReference("refund:1:3", "refund", 1, "pay", "payment")));
        references.replaceForDefinition("t2", "order:1:9", List.of(
                new CallReference("order:1:9", "order", 1, "pay", "payment")));

        assertThat(references.findCallers("t1", "payment")).extracting(CallReference::parentDefinitionKey)
                .containsExactly("order", "refund");
        assertThat(references.findAll("t1")).hasSize(3);

        references.replaceForDefinition("t1", "order:1:1", List.of(
                new CallReference("order:1:1", "order", 1, "ship", "shipping")));
        assertThat(references.findByDefinition("t1", "order:1:1")).extracting(CallReference::activityId)
                .containsExactly("ship");

        references.deleteForKey("t1", "refund");
        assertThat(references.findCallers("t1", "payment")).isEmpty();
        assertThat(references.findCallers("t2", "payment")).hasSize(1);
    }
}
