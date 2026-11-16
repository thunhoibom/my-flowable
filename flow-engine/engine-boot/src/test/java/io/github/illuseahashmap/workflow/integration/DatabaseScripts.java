package io.github.illuseahashmap.workflow.integration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Applies the manual DB scripts ({@code flow-engine/db/V*.sql}, copied to {@code classpath:db/}) to a fresh test
 * database, in version order — the same steps a person runs by hand with psql.
 */
public final class DatabaseScripts {

    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    private DatabaseScripts() {
    }

    public static void apply(PostgreSQLContainer<?> postgres) {
        apply(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    public static void apply(String jdbcUrl, String username, String password) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            // Scripts after V16 write to tables with forced row-level security.
            statement.execute("SELECT set_config('app.system_worker', 'true', false)");
            for (Resource script : scripts()) {
                try {
                    statement.execute(script.getContentAsString(StandardCharsets.UTF_8));
                } catch (SQLException ex) {
                    throw new IllegalStateException("DB script failed: " + script.getFilename(), ex);
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not apply DB scripts", ex);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static List<Resource> scripts() throws IOException {
        Resource[] found = new PathMatchingResourcePatternResolver().getResources("classpath:db/V*__*.sql");
        if (found.length == 0) {
            throw new IllegalStateException("No DB scripts on the test classpath (expected flow-engine/db/V*.sql)");
        }
        return Arrays.stream(found).sorted(Comparator.comparingInt(DatabaseScripts::version)).toList();
    }

    private static int version(Resource script) {
        Matcher matcher = VERSION.matcher(String.valueOf(script.getFilename()));
        if (!matcher.matches()) {
            throw new IllegalStateException("Bad DB script name: " + script.getFilename());
        }
        return Integer.parseInt(matcher.group(1));
    }
}
