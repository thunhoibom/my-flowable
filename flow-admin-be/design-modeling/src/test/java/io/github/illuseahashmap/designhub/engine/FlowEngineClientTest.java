package io.github.illuseahashmap.designhub.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalContext;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Talks to a fake Flow Engine over real HTTP, and opens the service token the way Flow Engine does. */
class FlowEngineClientTest {

    private static final String SECRET = "shared-secret";
    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String response = "{\"code\":\"SUCCESS\",\"message\":\"success\",\"data\":null}";
    private FlowEngineClient client;

    private record Received(String method, String path, Map<String, List<String>> headers, byte[] body) {
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        client = new FlowEngineClient(new FlowEngineProperties("http://127.0.0.1:" + server.getAddress().getPort(),
                "flow-admin-be", SECRET, 3, "default", Duration.ofSeconds(2), Duration.ofSeconds(5), null));
        TenantContext.set(new TenantContext.TenantInfo("tenant-1", "t1", "Tenant 1"));
        CurrentPrincipalContext.set(new CurrentPrincipal("USER", "u1", "alice", "Alice", "t1", Set.of(), Set.of()));
    }

    @AfterEach
    void stop() {
        server.stop(0);
        TenantContext.clear();
        CurrentPrincipalContext.clear();
        UserToken.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                exchange.getRequestHeaders(), exchange.getRequestBody().readAllBytes()));
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @Test
    void signsDeploymentCallsWithATokenFlowEngineCanOpen() throws Exception {
        response = """
                {"code":"SUCCESS","message":"success","data":{"deploymentId":"d1","processDefinitionId":"p:2:x",
                 "processKey":"p","name":"P","version":2,"suspended":false,"deployTime":"2026-11-13T10:00:00Z",
                 "newUserTaskIds":["approve"]}}""";

        FlowEngineClient.Process process = client.deployProcess(Map.of("publishId", "pub-1", "processKey", "p"));

        assertThat(process.version()).isEqualTo(2);
        assertThat(process.newUserTaskIds()).containsExactly("approve");
        Received request = received.getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/api/deployment/processes");
        assertThat(request.headers().get("X-acting-user")).containsExactly("alice");
        JsonNode payload = open(request.headers().get("X-workflow-token").getFirst());
        assertThat(payload.path("clientCode").asText()).isEqualTo("flow-admin-be");
        assertThat(payload.path("tenantCode").asText()).isEqualTo("t1");
        assertThat(payload.path("method").asText()).isEqualTo("POST");
        assertThat(payload.path("path").asText()).isEqualTo("/api/deployment/processes");
        assertThat(payload.path("tokenVersion").asInt()).isEqualTo(3);
        assertThat(payload.path("bodySha256").asText()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(request.body())));
        assertThat(payload.path("nonce").asText()).isNotBlank();
    }

    @Test
    void anEmptyBodyIsSignedToo() throws Exception {
        client.deleteProcess("order approval");

        Received request = received.getFirst();
        assertThat(request.path()).isEqualTo("/api/deployment/processes/order%20approval");
        JsonNode payload = open(request.headers().get("X-workflow-token").getFirst());
        assertThat(payload.path("path").asText()).isEqualTo("/api/deployment/processes/order%20approval");
        assertThat(payload.path("bodySha256").asText()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(new byte[0])));
    }

    @Test
    void returnsTheErrorOfTheEngineUnchangedAndNotFoundAsEmpty() {
        status = 400;
        response = "{\"code\":\"BAD_REQUEST\",\"message\":\"Các dịch vụ chưa có cấu hình môi trường: crm\"}";
        assertThatThrownBy(() -> client.deployProcess(Map.of()))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(error.getMessage()).contains("crm");
                });

        status = 404;
        response = "{\"code\":\"NOT_FOUND\",\"message\":\"Process p is not deployed\"}";
        assertThat(client.process("p")).isEmpty();
        assertThat(client.event("e")).isEmpty();

        status = 502;
        response = "<html>bad gateway</html>";
        assertThatThrownBy(() -> client.processes()).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.WORKFLOW_ERROR));
    }

    @Test
    void adminCallsCarryTheUserTokenAndTenant() {
        response = """
                {"code":"SUCCESS","message":"success","data":{"principalType":"USER","userId":"u1",
                 "username":"alice","tenantId":"tenant-1","tenantCode":"t1","tenantName":"T1",
                 "roles":["WF_ADMIN"]}}""";

        FlowEngineClient.Me me = client.me("user-token", "t1");

        assertThat(me.tenantId()).isEqualTo("tenant-1");
        assertThat(me.roles()).containsExactly("WF_ADMIN");
        assertThat(received.getFirst().headers().get("Authorization")).containsExactly("Bearer user-token");
        assertThat(received.getFirst().headers().get("X-tenant-code")).containsExactly("t1");
        assertThat(received.getFirst().headers()).doesNotContainKey("X-workflow-token");

        response = "{\"code\":\"SUCCESS\",\"message\":\"success\",\"data\":null}";
        UserToken.set("user-token");
        client.saveServiceEnvironment("crm", Map.of("baseUrl", "https://crm.example"));
        Received save = received.get(1);
        assertThat(save.method()).isEqualTo("PUT");
        assertThat(save.path()).isEqualTo("/api/admin/service-environments/crm");
        assertThat(save.headers().get("Authorization")).containsExactly("Bearer user-token");

        UserToken.clear();
        assertThatThrownBy(() -> client.saveServiceEnvironment("crm", Map.of()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    void refusesToCallWithoutConfiguration() {
        FlowEngineClient unconfigured = new FlowEngineClient(new FlowEngineProperties("", "flow-admin-be", "",
                1, null, null, null, null));
        assertThatThrownBy(() -> unconfigured.processes()).isInstanceOf(BusinessException.class);
        FlowEngineClient noSecret = new FlowEngineClient(new FlowEngineProperties("http://127.0.0.1:9",
                "flow-admin-be", " ", 1, null, null, null, null));
        assertThatThrownBy(() -> noSecret.processes()).hasMessageContaining("service client");
    }

    /** What Flow Engine's ServiceTokenCryptoService does. */
    private static JsonNode open(String token) throws Exception {
        int separator = token.indexOf('.');
        assertThat(token.substring(0, separator)).isEqualTo("flow-admin-be");
        byte[] bytes = Base64.getUrlDecoder().decode(token.substring(separator + 1));
        byte[] key = MessageDigest.getInstance("SHA-256").digest(SECRET.getBytes(StandardCharsets.UTF_8));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, Arrays.copyOfRange(bytes, 0, 12)));
        return JSON.readTree(cipher.doFinal(Arrays.copyOfRange(bytes, 12, bytes.length)));
    }
}
