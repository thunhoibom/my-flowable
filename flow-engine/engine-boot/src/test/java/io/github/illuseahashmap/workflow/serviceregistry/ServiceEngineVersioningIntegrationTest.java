package io.github.illuseahashmap.workflow.serviceregistry;

import io.github.illuseahashmap.workflow.integration.DatabaseScripts;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.illuseahashmap.serviceregistry.domain.AuthType;
import io.github.illuseahashmap.serviceregistry.domain.HttpMethod;
import io.github.illuseahashmap.serviceregistry.domain.PublishedOperation;
import io.github.illuseahashmap.serviceregistry.domain.PublishedService;
import io.github.illuseahashmap.serviceregistry.engine.ServiceModelJson;
import io.github.illuseahashmap.serviceregistry.infrastructure.JdbcTenantVariableStore;
import io.github.illuseahashmap.workflow.WorkflowAgentServiceApplication;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.DeploymentBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The goal of the service engine: a running instance keeps calling the service as it was deployed with its
 * process version, even after the service is republished and the process is deployed again.
 */
@SpringBootTest(classes = WorkflowAgentServiceApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ServiceEngineVersioningIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static HttpServer upstream;
    private static final List<String> CALLS = new CopyOnWriteArrayList<>();

    @Autowired
    private RepositoryService repositoryService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private JdbcTenantVariableStore tenantVariables;

    private final java.util.Map<String, PublishedService> designs = new java.util.HashMap<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Schema is managed by hand (flow-engine/db); tests apply the same scripts.
        DatabaseScripts.apply(POSTGRES);
        registry.add("flowable.database-schema-update", () -> "true");
        registry.add("flowable.async-executor-activate", () -> "false");
        registry.add("workflow.agent.worker.enabled", () -> "false");
        registry.add("workflow.security.enabled", () -> "false");
        registry.add("workflow.auth.keycloak.server-url", () -> "http://127.0.0.1:9");
        registry.add("workflow.auth.keycloak.realm", () -> "rsa");
        registry.add("workflow.auth.keycloak.client-id", () -> "VETC.WORKFLOW");
        registry.add("workflow.runtime.environment", () -> "test");
        registry.add("workflow.services.environment", () -> "default");
    }

    @BeforeAll
    static void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            CALLS.add(exchange.getRequestURI().getPath());
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop(0);
    }

    @BeforeEach
    void clearCalls() {
        CALLS.clear();
    }

    @Test
    void runningInstancesKeepTheServiceVersionTheirProcessWasDeployedWith() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String serviceKey = "crm_" + suffix;
        String processKey = "orders_" + suffix;

        publishService(serviceKey, "/a");
        system(() -> deploy(processKey, serviceKey, true));
        String first = system(() -> runtimeService.startProcessInstanceByKey(processKey).getId());

        publishService(serviceKey, "/b");
        system(() -> deploy(processKey, serviceKey, true));
        String second = system(() -> runtimeService.startProcessInstanceByKey(processKey).getId());

        completeWaitTask(first);
        completeWaitTask(second);

        assertThat(CALLS).containsExactly("/a", "/b");
    }

    @Test
    void sameDeploymentFalseAlwaysCallsTheLatestDeployedVersion() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String serviceKey = "erp_" + suffix;
        String processKey = "latest_" + suffix;

        publishService(serviceKey, "/a");
        system(() -> deploy(processKey, serviceKey, false));
        String first = system(() -> runtimeService.startProcessInstanceByKey(processKey).getId());

        publishService(serviceKey, "/b");
        system(() -> deploy(processKey, serviceKey, false));

        completeWaitTask(first);

        assertThat(CALLS).containsExactly("/b");
    }

    /**
     * What Flow Admin BE sends: the model of the service, whose base URL reads a tenant variable through
     * Flowable's expression manager; the variable is set on this engine.
     */
    private void publishService(String serviceKey, String path) {
        system(() -> {
            tenantVariables.save("", "url_" + serviceKey,
                    "http://127.0.0.1:" + upstream.getAddress().getPort(), false, "test");
            return null;
        });
        designs.put(serviceKey, new PublishedService(serviceKey, "CRM", null, "${tenant.url_" + serviceKey + "}", null, List.of(
                new PublishedOperation("call", HttpMethod.GET, path, AuthType.NONE, null, null, null, 2000,
                        List.of("500"), "CALL_FAILED", List.of(), List.of()))));
    }

    /** What the Deployment API does: the BPMN and the services it calls in one Flowable deployment. */
    private String deploy(String processKey, String serviceKey, boolean sameDeployment) {
        DeploymentBuilder builder = repositoryService.createDeployment()
                .key(processKey)
                .addString(processKey + ".bpmn20.xml", bpmn(processKey, serviceKey, sameDeployment));
        builder.addBytes(ServiceModelJson.resourceName(serviceKey), new ServiceModelJson().write(designs.get(serviceKey)));
        return builder.deploy().getId();
    }

    private void completeWaitTask(String processInstanceId) {
        system(() -> {
            String taskId = taskService.createTaskQuery().processInstanceId(processInstanceId).singleResult().getId();
            taskService.complete(taskId);
            return null;
        });
    }

    private static String bpmn(String processKey, String serviceKey, boolean sameDeployment) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             xmlns:workflow="http://workflow-agent.local/bpmn"
                             targetNamespace="service-engine-test">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="wait"/>
                    <userTask id="wait"/>
                    <sequenceFlow id="f2" sourceRef="wait" targetRef="call"/>
                    <serviceTask id="call" flowable:delegateExpression="${serviceRegistryDelegate}">
                      <extensionElements>
                        <workflow:serviceCall serviceKey="%s" operationKey="call" sameDeployment="%s"
                            inputMapping="[]" outputMapping="[]"/>
                      </extensionElements>
                    </serviceTask>
                    <sequenceFlow id="f3" sourceRef="call" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(processKey, serviceKey, sameDeployment);
    }

    private static <T> T system(java.util.function.Supplier<T> action) {
        return TrustedDataAccessContext.runAsSystemWorker(action);
    }
}
