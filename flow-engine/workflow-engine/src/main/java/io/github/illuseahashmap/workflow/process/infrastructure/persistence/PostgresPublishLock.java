package io.github.illuseahashmap.workflow.process.infrastructure.persistence;

import io.github.illuseahashmap.workflow.process.application.port.PublishLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** A transaction-scoped Postgres advisory lock on (tenant, publish id). Released at commit or rollback. */
@Component
public class PostgresPublishLock implements PublishLock {

    private final JdbcTemplate jdbc;

    public PostgresPublishLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lock(String tenantId, String publishId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", resultSet -> null,
                "process-publish:" + tenantId + ":" + publishId);
    }
}
