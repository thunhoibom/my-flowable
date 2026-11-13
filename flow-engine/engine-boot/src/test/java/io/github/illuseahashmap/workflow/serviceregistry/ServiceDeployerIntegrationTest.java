package io.github.illuseahashmap.workflow.serviceregistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.illuseahashmap.serviceregistry.domain.AuthType;
import io.github.illuseahashmap.serviceregistry.domain.HttpMethod;
import io.github.illuseahashmap.serviceregistry.domain.PublishedOperation;
import io.github.illuseahashmap.serviceregistry.domain.PublishedService;
import io.github.illuseahashmap.serviceregistry.engine.ServiceDeployment;
import io.github.illuseahashmap.serviceregistry.engine.ServiceModelJson;
import io.github.illuseahashmap.serviceregistry.engine.ServiceRepositoryService;
import io.github.illuseahashmap.workflow.WorkflowAgentServiceApplication;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = WorkflowAgentServiceApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ServiceDeployerIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private RepositoryService repositoryService;
    @Autowired
    private ServiceRepositoryService serviceRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private final ServiceModelJson json = new ServiceModelJson();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.flyway.baseline-version", () -> "0");
        registry.add("flowable.database-schema-update", () -> "true");
        registry.add("flowable.async-executor-activate", () -> "false");
        registry.add("workflow.agent.worker.enabled", () -> "false");
        registry.add("workflow.security.enabled", () -> "false");
        registry.add("workflow.auth.keycloak.server-url", () -> "http://127.0.0.1:9");
        registry.add("workflow.auth.keycloak.realm", () -> "rsa");
        registry.add("workflow.auth.keycloak.client-id", () -> "VETC.WORKFLOW");
        registry.add("workflow.runtime.environment", () -> "test");
    }

    @Test
    void serviceResourcesBecomeAChildDeploymentOfTheProcessDeployment() {
        String key = "svc_" + UUID.randomUUID().toString().replace("-", "");
        Deployment deployment = system(() -> repositoryService.createDeployment()
                .name("orders")
                .tenantId("tenant-a")
                .addBytes(key + ".bpmn20.xml", bpmn(key))
                .addBytes(ServiceModelJson.resourceName(key), json.write(service(key, "/a")))
                .deploy());

        assertThat(deployment.getParentDeploymentId()).isEqualTo(deployment.getId());
        List<ServiceDeployment> children = serviceRepository.findDeploymentsByParentDeploymentId(deployment.getId());
        assertThat(children).singleElement().satisfies(child -> {
            assertThat(child.tenantId()).isEqualTo("tenant-a");
            assertThat(child.name()).isEqualTo("orders");
        });
        assertThat(serviceRepository.getServiceModelByKeyAndParentDeploymentId(key, deployment.getId(), "tenant-a")
                .operations().getFirst().urlTemplate()).isEqualTo("/a");

        systemRun(() -> repositoryService.deleteDeployment(deployment.getId(), true));

        assertThat(serviceRepository.findDeploymentsByParentDeploymentId(deployment.getId())).isEmpty();
    }

    @Test
    void deploymentWithoutServiceResourcesCreatesNoServiceDeployment() {
        String key = "plain_" + UUID.randomUUID().toString().replace("-", "");
        Deployment deployment = system(() -> repositoryService.createDeployment()
                .addBytes(key + ".bpmn20.xml", bpmn(key))
                .deploy());

        assertThat(serviceRepository.findDeploymentsByParentDeploymentId(deployment.getId())).isEmpty();
    }

    @Test
    void serviceDeploymentRollsBackWithTheProcessDeployment() {
        String key = "rollback_" + UUID.randomUUID().toString().replace("-", "");

        assertThatThrownBy(() -> systemRun(() -> transactionTemplate.executeWithoutResult(status -> {
            repositoryService.createDeployment()
                    .addBytes(key + ".bpmn20.xml", bpmn(key))
                    .addBytes(ServiceModelJson.resourceName(key), json.write(service(key, "/a")))
                    .deploy();
            throw new IllegalStateException("rollback");
        }))).hasMessage("rollback");

        assertThat(serviceRepository.findLatestDefinition(key, ServiceRepositoryService.NO_TENANT_ID)).isEmpty();
    }

    private static PublishedService service(String key, String path) {
        return new PublishedService(key, key, null, List.of(), null, List.of(new PublishedOperation(
                "call", HttpMethod.GET, path, AuthType.NONE, null, null, null, 1000, List.of("500"), "FAILED",
                List.of(), List.of())));
    }

    private static byte[] bpmn(String key) {
        return ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    targetNamespace="service-deployer-test">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="flow" sourceRef="start" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key)).getBytes(StandardCharsets.UTF_8);
    }

    private static <T> T system(java.util.function.Supplier<T> action) {
        return TrustedDataAccessContext.runAsSystemWorker(action);
    }

    private static void systemRun(Runnable action) {
        TrustedDataAccessContext.runAsSystemWorker(action);
    }
}
