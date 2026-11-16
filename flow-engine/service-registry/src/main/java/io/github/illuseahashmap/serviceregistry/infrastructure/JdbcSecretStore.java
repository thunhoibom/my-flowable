package io.github.illuseahashmap.serviceregistry.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code work_secret}: named secrets of this Flow Engine, like Flowable Hub "Secrets". The value is stored only
 * encrypted and is never read back through the Admin API; changing a secret means deleting and creating it again.
 */
public final class JdbcSecretStore {

    private final JdbcTemplate jdbcTemplate;
    private final ServiceSecretCipher cipher;

    public JdbcSecretStore(DataSource dataSource, ServiceSecretCipher cipher) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.cipher = cipher;
    }

    /** A secret without its value. */
    public record Entry(String name, String createdBy, Instant createdAt) {
    }

    public List<Entry> list(String tenantId) {
        return jdbcTemplate.query("""
                SELECT name, created_by, created_at FROM work_secret WHERE tenant_id = ? ORDER BY name
                """, (resultSet, rowNumber) -> new Entry(resultSet.getString("name"),
                resultSet.getString("created_by"), resultSet.getTimestamp("created_at").toInstant()), tenantId);
    }

    public boolean exists(String tenantId, String name) {
        return !jdbcTemplate.queryForList("SELECT 1 FROM work_secret WHERE tenant_id = ? AND name = ?",
                Integer.class, tenantId, name).isEmpty();
    }

    /** @return false when a secret with this name already exists (it is left unchanged) */
    public boolean create(String tenantId, String name, String value, String createdBy) {
        return jdbcTemplate.update("""
                INSERT INTO work_secret (tenant_id, name, value_cipher, created_by, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, name) DO NOTHING
                """, tenantId, name, cipher.encrypt(value), createdBy, Timestamp.from(Instant.now())) > 0;
    }

    public boolean delete(String tenantId, String name) {
        return jdbcTemplate.update("DELETE FROM work_secret WHERE tenant_id = ? AND name = ?", tenantId, name) > 0;
    }

    /** The decrypted value, for a service call only. */
    public Optional<String> value(String tenantId, String name) {
        return jdbcTemplate.queryForList("SELECT value_cipher FROM work_secret WHERE tenant_id = ? AND name = ?",
                String.class, tenantId, name).stream().findFirst().map(cipher::decrypt);
    }

    /** The names among {@code names} that have no secret in this tenant. */
    public Set<String> missing(String tenantId, Collection<String> names) {
        Set<String> missing = new TreeSet<>(names);
        if (!missing.isEmpty()) {
            missing.removeAll(jdbcTemplate.queryForList(
                    "SELECT name FROM work_secret WHERE tenant_id = ? AND name = ANY (?)", String.class,
                    tenantId, missing.toArray(String[]::new)));
        }
        return missing;
    }
}
