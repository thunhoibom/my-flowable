package io.github.illuseahashmap.serviceregistry.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.illuseahashmap.serviceregistry.domain.AuthConfig;
import io.github.illuseahashmap.serviceregistry.domain.AuthType;
import io.github.illuseahashmap.serviceregistry.domain.EnvironmentBaseUrl;
import io.github.illuseahashmap.serviceregistry.domain.HttpMethod;
import io.github.illuseahashmap.serviceregistry.domain.PublishedOperation;
import io.github.illuseahashmap.serviceregistry.domain.PublishedService;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcServiceRepositoryServiceTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private final ServiceModelJson json = new ServiceModelJson();

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            for (String script : List.of(
                    "V32__add_service_registry.sql",
                    "V33__add_service_operation_parameters.sql",
                    "V34__service_catalog_foundation.sql",
                    "V35__service_http_content.sql",
                    "V36__service_extended_auth.sql",
                    "V37__service_multi_step.sql",
                    "V41__service_engine.sql")) {
                executeScript(statement, "/db/migration/platform/" + script);
            }
        }
    }

    @Test
    void versionsCountPerKeyAndTenantLikeFlowable() {
        var repository = repository(null);
        String crm = unique("crm");
        String erp = unique("erp");

        deploy(repository, "p1", "t1", service(crm, "/a"), service(erp, "/x"));
        deploy(repository, "p2", "t1", service(crm, "/b"));
        deploy(repository, "p3", "t2", service(crm, "/c"));

        assertThat(repository.findLatestDefinition(crm, "t1").orElseThrow().version()).isEqualTo(2);
        assertThat(repository.findLatestDefinition(erp, "t1").orElseThrow().version()).isEqualTo(1);
        assertThat(repository.findLatestDefinition(crm, "t2").orElseThrow().version()).isEqualTo(1);
    }

    @Test
    void lookupUsesTheDefinitionOfTheParentDeploymentNotTheLatest() {
        var repository = repository(null);
        String crm = unique("crm");
        String parentV1 = unique("process-v1");
        String parentV2 = unique("process-v2");

        deploy(repository, parentV1, "t1", service(crm, "/a"));
        deploy(repository, parentV2, "t1", service(crm, "/b"));

        assertThat(path(repository.getServiceModelByKeyAndParentDeploymentId(crm, parentV1, "t1"))).isEqualTo("/a");
        assertThat(path(repository.getServiceModelByKeyAndParentDeploymentId(crm, parentV2, "t1"))).isEqualTo("/b");
        assertThat(path(repository.getServiceModelByKey(crm, "t1"))).isEqualTo("/b");
    }

    @Test
    void parentWithoutTheKeyFallsBackToTheLatestVersionInTheTenant() {
        var repository = repository(null);
        String crm = unique("crm");
        deploy(repository, unique("process"), "t1", service(crm, "/a"));
        deploy(repository, unique("process"), "t1", service(crm, "/b"));

        assertThat(path(repository.getServiceModelByKeyAndParentDeploymentId(crm, "unknown-parent", "t1")))
                .isEqualTo("/b");
        assertThatThrownBy(() -> repository.getServiceModelByKeyAndParentDeploymentId(crm, "unknown-parent", "t9"))
                .isInstanceOf(ServiceDefinitionNotFoundException.class);
    }

    @Test
    void defaultTenantFallbackIsUsedOnlyWhenConfigured() {
        String crm = unique("crm");
        deploy(repository(null), unique("process"), "shared", service(crm, "/shared"));

        assertThat(path(repository("shared").getServiceModelByKey(crm, "t1"))).isEqualTo("/shared");
        assertThatThrownBy(() -> repository(null).getServiceModelByKey(crm, "t1"))
                .isInstanceOf(ServiceDefinitionNotFoundException.class);
    }

    @Test
    void resourceKeepsTheDesignButNeverBaseUrlsOrSecrets() {
        var repository = repository(null);
        String crm = unique("crm");
        PublishedService withSecrets = new PublishedService(crm, "CRM", "leads",
                List.of(new EnvironmentBaseUrl("default", "https://crm.example")),
                new AuthConfig(AuthType.BEARER, null, "shared-secret"),
                List.of(new PublishedOperation("createLead", HttpMethod.POST, "/leads",
                        AuthType.BEARER, null, "operation-secret", null, 1000, List.of("409"), "LEAD_CONFLICT",
                        List.of(), List.of())));
        ServiceDeployment deployment = repository.createDeployment()
                .tenantId("t1")
                .parentDeploymentId(unique("process"))
                .addServiceDefinitionBytes(ServiceModelJson.resourceName(crm), json.write(withSecrets))
                .deploy();

        String stored = new String(repository.getResource(deployment.id(), crm + ".service"), StandardCharsets.UTF_8);
        assertThat(stored).doesNotContain("shared-secret", "operation-secret", "crm.example");
        PublishedService model = repository.getServiceModelByKey(crm, "t1");
        assertThat(model.name()).isEqualTo("CRM");
        assertThat(model.operations().getFirst().urlTemplate()).isEqualTo("/leads");
        assertThat(model.operations().getFirst().errorRules()).isNotEmpty();
        assertThat(model.baseUrls()).isEmpty();
    }

    @Test
    void deletingADeploymentRemovesItsDefinitionsAndResources() {
        var repository = repository(null);
        String crm = unique("crm");
        String parent = unique("process");
        deploy(repository, parent, "t1", service(crm, "/a"));
        ServiceDeployment deployment = repository.findDeploymentsByParentDeploymentId(parent).getFirst();
        repository.getServiceModelByKeyAndParentDeploymentId(crm, parent, "t1");

        repository.deleteDeployment(deployment.id());

        assertThat(repository.findDeploymentsByParentDeploymentId(parent)).isEmpty();
        assertThat(repository.findDefinitionsByDeploymentId(deployment.id())).isEmpty();
        assertThatThrownBy(() -> repository.getServiceModelByKeyAndParentDeploymentId(crm, parent, "t1"))
                .isInstanceOf(ServiceDefinitionNotFoundException.class);
    }

    @Test
    void oneDeploymentCannotCarryTheSameKeyTwice() {
        var repository = repository(null);
        String crm = unique("crm");
        var builder = repository.createDeployment()
                .tenantId("t1")
                .addServiceDefinitionBytes("one.service", json.write(service(crm, "/a")))
                .addServiceDefinitionBytes("two.service", json.write(service(crm, "/b")));

        assertThatThrownBy(builder::deploy).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resourceNamesMustEndWithServiceSuffix() {
        assertThatThrownBy(() -> repository(null).createDeployment().addServiceDefinitionBytes("crm.json", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void deploy(JdbcServiceRepositoryService repository, String parent, String tenant,
                        PublishedService... services) {
        var builder = repository.createDeployment().name("process").tenantId(tenant).parentDeploymentId(parent);
        for (PublishedService service : services) {
            builder.addServiceDefinitionBytes(ServiceModelJson.resourceName(service.serviceKey()), json.write(service));
        }
        builder.deploy();
    }

    private static PublishedService service(String key, String path) {
        return new PublishedService(key, key, null, List.of(), null, List.of(new PublishedOperation(
                "call", HttpMethod.GET, path, AuthType.NONE, null, null, null, 1000, List.of("500"), "FAILED",
                List.of(), List.of())));
    }

    private static String path(PublishedService service) {
        return service.operations().getFirst().urlTemplate();
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static JdbcServiceRepositoryService repository(String defaultTenantId) {
        return new JdbcServiceRepositoryService(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()), 200, false, defaultTenantId);
    }

    private static void executeScript(Statement statement, String resource) throws Exception {
        String sql = new String(JdbcServiceRepositoryServiceTest.class.getResourceAsStream(resource).readAllBytes(),
                StandardCharsets.UTF_8);
        for (String command : sql.split(";")) {
            if (!command.isBlank()) {
                statement.execute(command);
            }
        }
    }
}
