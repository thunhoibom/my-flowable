package io.github.illuseahashmap.serviceregistry.infrastructure;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.serviceregistry.application.ServiceSettingsSource;
import io.github.illuseahashmap.serviceregistry.domain.AuthConfig;
import io.github.illuseahashmap.serviceregistry.domain.AuthDetails;
import io.github.illuseahashmap.serviceregistry.domain.ServiceSettings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code work_service_environment}: base URL and credentials of each service on this Flow Engine.
 * Secrets (secret, client certificate, access key) are stored only as one cipher; the JSON column holds the
 * rest. A save that leaves a secret null keeps the stored one, so an operator can change a URL without
 * re-entering every credential.
 */
public final class JdbcServiceEnvironmentStore implements ServiceSettingsSource {

    private final JdbcTemplate jdbcTemplate;
    private final ServiceSecretCipher cipher;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public JdbcServiceEnvironmentStore(DataSource dataSource, ServiceSecretCipher cipher) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.cipher = cipher;
    }

    /** One stored row with its secrets removed, and the names of the secrets that are stored. */
    public record Entry(String serviceKey, ServiceSettings settings, Set<String> storedSecrets,
                        String updatedBy, Instant updatedAt) {
    }

    @Override
    public Optional<ServiceSettings> find(String tenantId, String serviceKey) {
        return row(tenantId, serviceKey).map(Row::settings);
    }

    public Optional<Entry> entry(String tenantId, String serviceKey) {
        return row(tenantId, serviceKey).map(Row::entry);
    }

    public List<Entry> list(String tenantId) {
        return jdbcTemplate.query("""
                SELECT service_key, base_url, auth_config, secret_cipher, updated_by, updated_at
                FROM work_service_environment WHERE tenant_id = ? ORDER BY service_key
                """, this::mapRow, tenantId).stream().map(Row::entry).toList();
    }

    public void save(String tenantId, String serviceKey, ServiceSettings incoming, String updatedBy) {
        ServiceSettings merged = row(tenantId, serviceKey)
                .map(stored -> keepStoredSecrets(incoming, stored.settings()))
                .orElse(incoming);
        Secrets secrets = Secrets.of(merged);
        jdbcTemplate.update("""
                INSERT INTO work_service_environment
                    (tenant_id, service_key, base_url, auth_config, secret_cipher, updated_by, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, service_key) DO UPDATE SET
                    base_url = EXCLUDED.base_url,
                    auth_config = EXCLUDED.auth_config,
                    secret_cipher = EXCLUDED.secret_cipher,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = EXCLUDED.updated_at
                """, tenantId, serviceKey, blankToNull(merged.baseUrl()), write(PublicPart.of(merged)),
                secrets.isEmpty() ? null : cipher.encrypt(write(secrets)), updatedBy, Timestamp.from(Instant.now()));
    }

    public boolean delete(String tenantId, String serviceKey) {
        return jdbcTemplate.update("DELETE FROM work_service_environment WHERE tenant_id = ? AND service_key = ?",
                tenantId, serviceKey) > 0;
    }

    private Optional<Row> row(String tenantId, String serviceKey) {
        return jdbcTemplate.query("""
                SELECT service_key, base_url, auth_config, secret_cipher, updated_by, updated_at
                FROM work_service_environment WHERE tenant_id = ? AND service_key = ?
                """, this::mapRow, tenantId, serviceKey).stream().findFirst();
    }

    private Row mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        PublicPart publicPart = read(resultSet.getString("auth_config"), PublicPart.class);
        String secretCipher = resultSet.getString("secret_cipher");
        Secrets secrets = secretCipher == null ? Secrets.EMPTY : read(cipher.decrypt(secretCipher), Secrets.class);
        Timestamp updatedAt = resultSet.getTimestamp("updated_at");
        return new Row(resultSet.getString("service_key"), resultSet.getString("base_url"), publicPart, secrets,
                resultSet.getString("updated_by"), updatedAt == null ? null : updatedAt.toInstant());
    }

    private static ServiceSettings keepStoredSecrets(ServiceSettings incoming, ServiceSettings stored) {
        Map<String, ServiceSettings.OperationAuth> operations = new LinkedHashMap<>();
        incoming.operations().forEach((operationKey, auth) -> {
            ServiceSettings.OperationAuth previous = stored.operations().get(operationKey);
            operations.put(operationKey, new ServiceSettings.OperationAuth(auth.mode(),
                    previous == null ? auth.auth() : keep(auth.auth(), previous.auth())));
        });
        return new ServiceSettings(incoming.baseUrl(), keep(incoming.sharedAuth(), stored.sharedAuth()), operations);
    }

    private static AuthConfig keep(AuthConfig incoming, AuthConfig stored) {
        AuthDetails details = incoming.details();
        AuthDetails storedDetails = stored.details();
        return new AuthConfig(incoming.type(), incoming.apiKeyHeader(),
                incoming.secret() == null ? stored.secret() : incoming.secret(),
                incoming.apiKeyLocation(),
                new AuthDetails(details.tokenUrl(), details.clientId(), details.scopes(),
                        details.certificatePem() == null ? storedDetails.certificatePem() : details.certificatePem(),
                        details.bearerVariable(),
                        details.accessKey() == null ? storedDetails.accessKey() : details.accessKey(),
                        details.region(), details.awsService(), details.signatureHeader()));
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Row(String serviceKey, String baseUrl, PublicPart publicPart, Secrets secrets,
                       String updatedBy, Instant updatedAt) {

        ServiceSettings settings() {
            Map<String, ServiceSettings.OperationAuth> operations = new LinkedHashMap<>();
            publicPart.operations().forEach((operationKey, auth) -> operations.put(operationKey,
                    new ServiceSettings.OperationAuth(auth.mode(),
                            secrets.operation(operationKey).applyTo(auth.auth()))));
            return new ServiceSettings(baseUrl, secrets.shared().applyTo(publicPart.sharedAuth()), operations);
        }

        Entry entry() {
            Set<String> stored = new TreeSet<>();
            secrets.shared().names("shared").forEach(stored::add);
            secrets.operations().forEach((operationKey, values) ->
                    values.names("operations." + operationKey).forEach(stored::add));
            return new Entry(serviceKey, PublicPart.of(settings()).settings(baseUrl), Set.copyOf(stored),
                    updatedBy, updatedAt);
        }
    }

    /** Everything but the secrets. This is the JSON column. */
    private record PublicPart(AuthConfig sharedAuth, Map<String, ServiceSettings.OperationAuth> operations) {

        PublicPart {
            operations = operations == null ? Map.of() : operations;
        }

        static PublicPart of(ServiceSettings settings) {
            Map<String, ServiceSettings.OperationAuth> operations = new LinkedHashMap<>();
            settings.operations().forEach((operationKey, auth) -> operations.put(operationKey,
                    new ServiceSettings.OperationAuth(auth.mode(), withoutSecrets(auth.auth()))));
            return new PublicPart(withoutSecrets(settings.sharedAuth()), operations);
        }

        ServiceSettings settings(String baseUrl) {
            return new ServiceSettings(baseUrl, sharedAuth, operations);
        }

        private static AuthConfig withoutSecrets(AuthConfig auth) {
            return new AuthConfig(auth.type(), auth.apiKeyHeader(), null, auth.apiKeyLocation(),
                    Secret.of(auth).applyTo(auth.details(), true));
        }
    }

    /** The secrets of one auth config. */
    private record Secret(String secret, String certificatePem, String accessKey) {

        static final Secret NONE = new Secret(null, null, null);

        static Secret of(AuthConfig auth) {
            return new Secret(auth.secret(), auth.details().certificatePem(), auth.details().accessKey());
        }

        boolean isEmpty() {
            return secret == null && certificatePem == null && accessKey == null;
        }

        AuthConfig applyTo(AuthConfig auth) {
            if (auth == null) {
                return null;
            }
            return new AuthConfig(auth.type(), auth.apiKeyHeader(), secret, auth.apiKeyLocation(),
                    applyTo(auth.details(), false));
        }

        AuthDetails applyTo(AuthDetails details, boolean clear) {
            return new AuthDetails(details.tokenUrl(), details.clientId(), details.scopes(),
                    clear ? null : certificatePem, details.bearerVariable(), clear ? null : accessKey,
                    details.region(), details.awsService(), details.signatureHeader());
        }

        List<String> names(String prefix) {
            List<String> names = new java.util.ArrayList<>();
            if (secret != null) {
                names.add(prefix + ".secret");
            }
            if (certificatePem != null) {
                names.add(prefix + ".certificatePem");
            }
            if (accessKey != null) {
                names.add(prefix + ".accessKey");
            }
            return names;
        }
    }

    /** Every secret of a service. Encrypted as a whole into {@code secret_cipher}. */
    private record Secrets(Secret shared, Map<String, Secret> operations) {

        static final Secrets EMPTY = new Secrets(Secret.NONE, Map.of());

        Secrets {
            shared = shared == null ? Secret.NONE : shared;
            operations = operations == null ? Map.of() : operations;
        }

        static Secrets of(ServiceSettings settings) {
            Map<String, Secret> operations = new LinkedHashMap<>();
            settings.operations().forEach((operationKey, auth) -> {
                Secret secret = Secret.of(auth.auth());
                if (!secret.isEmpty()) {
                    operations.put(operationKey, secret);
                }
            });
            return new Secrets(Secret.of(settings.sharedAuth()), operations);
        }

        Secret operation(String operationKey) {
            return operations.getOrDefault(operationKey, Secret.NONE);
        }

        boolean isEmpty() {
            return shared.isEmpty() && operations.isEmpty();
        }
    }
}
