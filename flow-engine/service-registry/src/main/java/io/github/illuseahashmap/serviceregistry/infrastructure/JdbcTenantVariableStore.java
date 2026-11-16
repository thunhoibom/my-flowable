package io.github.illuseahashmap.serviceregistry.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
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
 * {@code work_tenant_variable}: tenant variables of this Flow Engine, like Flowable Hub "Tenant Variables", read in
 * expressions as {@code ${tenant.<name>}}. A protected variable is stored only encrypted, shown masked, and can
 * never become unprotected.
 */
public final class JdbcTenantVariableStore {

    public static final String MASK = "******";

    private final JdbcTemplate jdbcTemplate;
    private final ServiceSecretCipher cipher;

    public JdbcTenantVariableStore(DataSource dataSource, ServiceSecretCipher cipher) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.cipher = cipher;
    }

    /** A variable as the Admin API shows it: a protected value is masked. */
    public record Entry(String name, String value, boolean protectedValue, String updatedBy, Instant updatedAt) {
    }

    public List<Entry> list(String tenantId) {
        return jdbcTemplate.query("""
                SELECT name, value, protected, updated_by, updated_at
                FROM work_tenant_variable WHERE tenant_id = ? ORDER BY name
                """, JdbcTenantVariableStore::entry, tenantId);
    }

    public Optional<Entry> find(String tenantId, String name) {
        return jdbcTemplate.query("""
                SELECT name, value, protected, updated_by, updated_at
                FROM work_tenant_variable WHERE tenant_id = ? AND name = ?
                """, JdbcTenantVariableStore::entry, tenantId, name).stream().findFirst();
    }

    /**
     * A null value keeps the stored one of a protected variable.
     *
     * @throws IllegalArgumentException when a protected variable would become unprotected, or a value is missing
     */
    public void save(String tenantId, String name, String value, boolean protectedValue, String updatedBy) {
        Optional<Entry> stored = find(tenantId, name);
        if (stored.isPresent() && stored.get().protectedValue() && !protectedValue) {
            throw new IllegalArgumentException("A protected tenant variable cannot become unprotected");
        }
        String plain = null;
        String encrypted = null;
        if (protectedValue) {
            if (value == null) {
                encrypted = jdbcTemplate.queryForList("""
                        SELECT value_cipher FROM work_tenant_variable
                        WHERE tenant_id = ? AND name = ? AND protected
                        """, String.class, tenantId, name).stream().findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Value is required"));
            } else {
                encrypted = cipher.encrypt(value);
            }
        } else {
            if (value == null) {
                throw new IllegalArgumentException("Value is required");
            }
            plain = value;
        }
        jdbcTemplate.update("""
                INSERT INTO work_tenant_variable (tenant_id, name, value, value_cipher, protected, updated_by, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, name) DO UPDATE SET
                    value = EXCLUDED.value,
                    value_cipher = EXCLUDED.value_cipher,
                    protected = EXCLUDED.protected,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = EXCLUDED.updated_at
                """, tenantId, name, plain, encrypted, protectedValue, updatedBy, Timestamp.from(Instant.now()));
    }

    public boolean delete(String tenantId, String name) {
        return jdbcTemplate.update("DELETE FROM work_tenant_variable WHERE tenant_id = ? AND name = ?",
                tenantId, name) > 0;
    }

    /** The real value, protected ones decrypted, for expressions only. */
    public Optional<String> value(String tenantId, String name) {
        return jdbcTemplate.query("""
                SELECT value, value_cipher, protected FROM work_tenant_variable WHERE tenant_id = ? AND name = ?
                """, (resultSet, rowNumber) -> resultSet.getBoolean("protected")
                ? cipher.decrypt(resultSet.getString("value_cipher"))
                : resultSet.getString("value"), tenantId, name).stream().findFirst();
    }

    /** The names among {@code names} that have no variable in this tenant. */
    public Set<String> missing(String tenantId, Collection<String> names) {
        Set<String> missing = new TreeSet<>(names);
        if (!missing.isEmpty()) {
            missing.removeAll(jdbcTemplate.queryForList(
                    "SELECT name FROM work_tenant_variable WHERE tenant_id = ? AND name = ANY (?)", String.class,
                    tenantId, missing.toArray(String[]::new)));
        }
        return missing;
    }

    private static Entry entry(ResultSet resultSet, int rowNumber) throws SQLException {
        boolean protectedValue = resultSet.getBoolean("protected");
        return new Entry(resultSet.getString("name"), protectedValue ? MASK : resultSet.getString("value"),
                protectedValue, resultSet.getString("updated_by"), resultSet.getTimestamp("updated_at").toInstant());
    }
}
