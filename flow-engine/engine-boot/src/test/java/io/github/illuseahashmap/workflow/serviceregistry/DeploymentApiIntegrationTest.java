package io.github.illuseahashmap.workflow.serviceregistry;

import io.github.illuseahashmap.workflow.integration.DatabaseScripts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.illuseahashmap.serviceregistry.infrastructure.JdbcSecretStore;
import io.github.illuseahashmap.serviceregistry.infrastructure.JdbcTenantVariableStore;
import io.github.illuseahashmap.workflow.WorkflowAgentServiceApplication;
import io.github.illuseahashmap.workflow.eventregistry.application.EventDeploymentService;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventDeploymentRequest;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventFieldView;
import io.github.illuseahashmap.workflow.process.application.ProcessDeploymentService;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDeploymentRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDeploymentResult;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalContext;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.eventregistry.api.EventRepositoryService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
 * Phase 1 of the architecture spec, end to end on Postgres: Flow Engine receives deployments as plain JSON and
 * runs them on its own.
 * <ul>
 *   <li>Republishing a service does not change what a running instance calls.</li>
 *   <li>The base URL reads a tenant variable and the token a secret of this engine; both take effect at once.</li>
 *   <li>The same publish id deploys once.</li>
 *   <li>Deploying a process never changes an event.</li>
 * </ul>
 */
@SpringBootTest(classes = WorkflowAgentServiceApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeploymentApiIntegrationTest {

    private static final TenantContext.TenantInfo TENANT =
            new TenantContext.TenantInfo("tenant-deploy-api", "deploy-api", "Deploy API");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static HttpServer first;
    private static HttpServer second;
    private static final List<String> CALLS = new CopyOnWriteArrayList<>();

    @Autowired
    private ProcessDeploymentService processDeployments;
    @Autowired
    private EventDeploymentService eventDeployments;
    @Autowired
    private JdbcTenantVariableStore tenantVariables;
    @Autowired
    private JdbcSecretStore secrets;
    @Autowired
    private RepositoryService repositoryService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private EventRepositoryService eventRepositoryService;

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
        registry.add("workflow.event-registry.enabled", () -> "true");
        registry.add("workflow.service.secrets.master-key-base64", () -> java.util.Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
    }

    @BeforeAll
    static void startUpstreams() throws IOException {
        first = upstream("first");
        second = upstream("second");
    }

    @AfterAll
    static void stopUpstreams() {
        first.stop(0);
        second.stop(0);
    }

    @BeforeEach
    void enterTenant() {
        CALLS.clear();
        TenantContext.set(TENANT);
        CurrentPrincipalContext.set(new CurrentPrincipal("SERVICE", "flow-admin-be", "alice", "alice",
                TENANT.tenantCode(), Set.of(), Set.of()));
    }

    @AfterEach
    void leaveTenant() {
        TenantContext.clear();
        CurrentPrincipalContext.clear();
    }

    @Test
    void runningInstancesKeepTheServiceTheirVersionWasDeployedWithWhileTenantVariablesApplyAtOnce() {
        String suffix = suffix();
        String serviceKey = "crm_" + suffix;
        String processKey = "orders_" + suffix;
        saveEnvironment(serviceKey, first);

        system(() -> processDeployments.deploy(request(UUID.randomUUID().toString(), processKey, serviceKey, "/a")));
        String instance1 = system(() -> start(processKey));
        system(() -> processDeployments.deploy(request(UUID.randomUUID().toString(), processKey, serviceKey, "/b")));
        String instance2 = system(() -> start(processKey));

        completeWaitTask(instance1);
        saveEnvironment(serviceKey, second);
        completeWaitTask(instance2);

        assertThat(CALLS).containsExactly("first:/a:Bearer token-" + serviceKey, "second:/b:Bearer token-" + serviceKey);
    }

    @Test
    void theSamePublishIdDeploysOnce() {
        String suffix = suffix();
        String serviceKey = "erp_" + suffix;
        String processKey = "invoices_" + suffix;
        saveEnvironment(serviceKey, first);
        String publishId = UUID.randomUUID().toString();

        ProcessDeploymentResult deployed = system(() ->
                processDeployments.deploy(request(publishId, processKey, serviceKey, "/a")));
        ProcessDeploymentResult again = system(() ->
                processDeployments.deploy(request(publishId, processKey, serviceKey, "/a")));

        assertThat(again.deploymentId()).isEqualTo(deployed.deploymentId());
        assertThat(again.version()).isEqualTo(deployed.version());
        assertThat(system(() -> repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(TENANT.tenantId()).processDefinitionKey(processKey).count()))
                .isEqualTo(1L);
    }

    @Test
    void aServiceReferencingMissingConfigurationIsRejectedAndNothingIsDeployed() {
        String suffix = suffix();
        String processKey = "rejected_" + suffix;

        assertThatThrownBy(() -> system(() -> processDeployments.deploy(
                request(UUID.randomUUID().toString(), processKey, "nowhere_" + suffix, "/a"))))
                .hasMessageContaining("thiếu cấu hình")
                .hasMessageContaining("secret token_nowhere_" + suffix)
                .hasMessageContaining("biến tenant url_nowhere_" + suffix);
        assertThat(system(() -> repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(TENANT.tenantId()).processDefinitionKey(processKey).count()))
                .isZero();
    }

    @Test
    void deployingAProcessNeverChangesAnEvent() {
        String suffix = suffix();
        String eventKey = "paid" + suffix;
        EventFieldView orderId = new EventFieldView("orderId", "string", true, false, false);
        system(() -> eventDeployments.deploy(new EventDeploymentRequest(UUID.randomUUID().toString(), null,
                List.of(new EventDeploymentRequest.Event(eventKey, "Paid", null, List.of(orderId))), List.of())));

        String serviceKey = "crm_" + suffix;
        saveEnvironment(serviceKey, first);
        system(() -> processDeployments.deploy(request(UUID.randomUUID().toString(), "evt_" + suffix, serviceKey,
                "/a")));
        system(() -> eventDeployments.deploy(new EventDeploymentRequest(UUID.randomUUID().toString(), null,
                List.of(new EventDeploymentRequest.Event(eventKey, "Paid", null, List.of(orderId))), List.of())));

        assertThat(system(() -> eventRepositoryService.createEventDefinitionQuery()
                .eventDefinitionKey(eventKey).tenantId(TENANT.tenantId()).count())).isEqualTo(1L);
    }

    private void saveEnvironment(String serviceKey, HttpServer upstream) {
        system(() -> {
            // What operations set up in Hub: the URL of this environment and the secret the model names.
            tenantVariables.save(TENANT.tenantId(), "url_" + serviceKey,
                    "http://127.0.0.1:" + upstream.getAddress().getPort(), false, "ops");
            secrets.create(TENANT.tenantId(), "token_" + serviceKey, "token-" + serviceKey, "ops");
            return null;
        });
    }

    private String start(String processKey) {
        return runtimeService.createProcessInstanceBuilder().processDefinitionKey(processKey)
                .tenantId(TENANT.tenantId()).start().getId();
    }

    private void completeWaitTask(String processInstanceId) {
        system(() -> {
            taskService.complete(taskService.createTaskQuery().processInstanceId(processInstanceId)
                    .singleResult().getId());
            return null;
        });
    }

    /** What Flow Admin BE sends: BPMN and the model of the called service (references only, no secret), no rules. */
    private static ProcessDeploymentRequest request(String publishId, String processKey, String serviceKey,
                                                    String path) {
        Map<String, Object> service = Map.of(
                "serviceKey", serviceKey,
                "name", "CRM",
                "baseUrl", "${tenant.url_" + serviceKey + "}",
                "authorization", Map.of("type", "BEARER",
                        "value", Map.of("source", "SECRET", "value", "token_" + serviceKey)),
                "operations", List.of(Map.of(
                        "operationKey", "call",
                        "method", "GET",
                        "urlTemplate", path,
                        "authMode", "INHERIT",
                        "connectTimeoutMillis", 2000,
                        "readTimeoutMillis", 2000,
                        "successStatuses", List.of("200"))));
        return new ProcessDeploymentRequest(publishId, TENANT.tenantId(), processKey, processKey,
                bpmn(processKey, serviceKey), List.of(service), List.of(), null, null);
    }

    private static String bpmn(String processKey, String serviceKey) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             xmlns:workflow="http://workflow-agent.local/bpmn"
                             targetNamespace="deployment-api-test">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="wait"/>
                    <userTask id="wait" flowable:assignee="alice"/>
                    <sequenceFlow id="f2" sourceRef="wait" targetRef="call"/>
                    <serviceTask id="call" flowable:delegateExpression="${serviceRegistryDelegate}">
                      <extensionElements>
                        <workflow:serviceCall serviceKey="%s" operationKey="call"
                            inputMapping="[]" outputMapping="[]"/>
                      </extensionElements>
                    </serviceTask>
                    <sequenceFlow id="f3" sourceRef="call" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(processKey, serviceKey);
    }

    private static HttpServer upstream(String name) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            CALLS.add(name + ":" + exchange.getRequestURI().getPath() + ":"
                    + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static <T> T system(Supplier<T> action) {
        return TrustedDataAccessContext.runAsSystemWorker(action);
    }
}
