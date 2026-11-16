package io.github.illuseahashmap.serviceregistry.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code work_oauth2_client}: OAuth2 clients of this Flow Engine, like Flowable Hub "OAuth2 Clients". Service
 * models reference a client by registration key. Only the client secret is encrypted; it is never read back.
 */
public final class JdbcOauth2ClientStore {

    public static final String CLIENT_CREDENTIALS = "CLIENT_CREDENTIALS";

    private final JdbcTemplate jdbcTemplate;
    private final ServiceSecretCipher cipher;

    public JdbcOauth2ClientStore(DataSource dataSource, ServiceSecretCipher cipher) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.cipher = cipher;
    }

    /** A client without its secret. */
    public record Entry(String registrationKey, String clientId, String tokenUri, List<String> scopes,
                        String grantType, String updatedBy, Instant updatedAt) {
    }

    /** What a save sends. A null client secret keeps the stored one. */
    public record Input(String clientId, String clientSecret, String tokenUri, List<String> scopes, String grantType) {

        public Input {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }

    /** Everything a token request needs, secret included. */
    public record Registration(String registrationKey, String clientId, String clientSecret, String tokenUri,
                               List<String> scopes) {
    }

    public List<Entry> list(String tenantId) {
        return jdbcTemplate.query("""
                SELECT registration_key, client_id, token_uri, scopes, grant_type, updated_by, updated_at
                FROM work_oauth2_client WHERE tenant_id = ? ORDER BY registration_key
                """, JdbcOauth2ClientStore::entry, tenantId);
    }

    public Optional<Entry> find(String tenantId, String registrationKey) {
        return jdbcTemplate.query("""
                SELECT registration_key, client_id, token_uri, scopes, grant_type, updated_by, updated_at
                FROM work_oauth2_client WHERE tenant_id = ? AND registration_key = ?
                """, JdbcOauth2ClientStore::entry, tenantId, registrationKey).stream().findFirst();
    }

    /** @throws IllegalArgumentException when a new client has no secret */
    public void save(String tenantId, String registrationKey, Input input, String updatedBy) {
        String secretCipher = input.clientSecret() == null || input.clientSecret().isBlank()
                ? jdbcTemplate.queryForList("""
                        SELECT client_secret_cipher FROM work_oauth2_client
                        WHERE tenant_id = ? AND registration_key = ?
                        """, String.class, tenantId, registrationKey).stream().findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Client secret is required"))
                : cipher.encrypt(input.clientSecret());
        jdbcTemplate.update("""
                INSERT INTO work_oauth2_client (tenant_id, registration_key, client_id, client_secret_cipher,
                    token_uri, scopes, grant_type, updated_by, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, registration_key) DO UPDATE SET
                    client_id = EXCLUDED.client_id,
                    client_secret_cipher = EXCLUDED.client_secret_cipher,
                    token_uri = EXCLUDED.token_uri,
                    scopes = EXCLUDED.scopes,
                    grant_type = EXCLUDED.grant_type,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = EXCLUDED.updated_at
                """, tenantId, registrationKey, input.clientId(), secretCipher, input.tokenUri(),
                input.scopes().isEmpty() ? null : String.join(" ", input.scopes()),
                input.grantType() == null ? CLIENT_CREDENTIALS : input.grantType(), updatedBy,
                Timestamp.from(Instant.now()));
    }

    public boolean delete(String tenantId, String registrationKey) {
        return jdbcTemplate.update("DELETE FROM work_oauth2_client WHERE tenant_id = ? AND registration_key = ?",
                tenantId, registrationKey) > 0;
    }

    /** The client with its decrypted secret, for a service call only. */
    public Optional<Registration> registration(String tenantId, String registrationKey) {
        return jdbcTemplate.query("""
                SELECT registration_key, client_id, client_secret_cipher, token_uri, scopes
                FROM work_oauth2_client WHERE tenant_id = ? AND registration_key = ?
                """, (resultSet, rowNumber) -> new Registration(resultSet.getString("registration_key"),
                resultSet.getString("client_id"), cipher.decrypt(resultSet.getString("client_secret_cipher")),
                resultSet.getString("token_uri"), scopes(resultSet.getString("scopes"))), tenantId, registrationKey)
                .stream().findFirst();
    }

    /** The keys among {@code keys} that have no client in this tenant. */
    public Set<String> missing(String tenantId, Collection<String> keys) {
        Set<String> missing = new TreeSet<>(keys);
        if (!missing.isEmpty()) {
            missing.removeAll(jdbcTemplate.queryForList(
                    "SELECT registration_key FROM work_oauth2_client WHERE tenant_id = ? AND registration_key = ANY (?)",
                    String.class, tenantId, missing.toArray(String[]::new)));
        }
        return missing;
    }

    private static Entry entry(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Entry(resultSet.getString("registration_key"), resultSet.getString("client_id"),
                resultSet.getString("token_uri"), scopes(resultSet.getString("scopes")),
                resultSet.getString("grant_type"), resultSet.getString("updated_by"),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static List<String> scopes(String value) {
        return value == null || value.isBlank() ? List.of() : Arrays.stream(value.trim().split("\\s+")).toList();
    }
}
