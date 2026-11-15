package io.github.illuseahashmap.designhub.engine;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentRuleInheritResult;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventFieldView;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalContext;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Flow Admin BE's only way to Flow Engine (architecture spec, rule 1: calls go one way). Two kinds of calls:
 * <ul>
 *   <li>Deployment API ({@code /api/deployment/**}) with a service token: the engine checks the client, the path,
 *       the body hash and a nonce. {@code X-Acting-User} names the person for the audit.</li>
 *   <li>Admin API ({@code /api/admin/**}) with the access token of the user, so the engine applies that user's
 *       rights.</li>
 * </ul>
 * An error of the engine comes back as a {@link BusinessException} with the engine's code and message.
 */
public class FlowEngineClient {

    private static final String TOKEN_HEADER = "X-Workflow-Token";
    private static final String ACTING_USER_HEADER = "X-Acting-User";
    private static final String TENANT_HEADER = "X-Tenant-Code";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final FlowEngineProperties properties;
    private final HttpClient http;
    private final ObjectMapper json = JsonMapper.builder()
            .findAndAddModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    public FlowEngineClient(FlowEngineProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
    }

    /** A deployed process version, as the engine reports it. */
    public record Process(String deploymentId, String processDefinitionId, String processKey, String name,
                          int version, boolean suspended, OffsetDateTime deployTime,
                          AssignmentRuleInheritResult assignments, List<String> newUserTaskIds) {
    }

    /** BPMN and {@code .service} definitions of one deployed version. */
    public record ProcessVersion(String processKey, String name, int version, String processDefinitionId,
                                 String bpmnXml, Map<String, String> services) {
    }

    /** The latest deployed event or channel. {@code fields} is null for a channel. */
    public record Model(String key, int version, List<EventFieldView> fields) {
    }

    /** What one event deployment did. */
    public record EventDeployment(List<Item> events, List<Item> channels) {

        public record Item(String key, int version, boolean deployed) {
        }
    }

    /** The caller as Flow Engine sees it in the tenant it asked for. */
    public record Me(String principalType, String userId, String username, String displayName, String tenantId,
                     String tenantCode, String tenantName, Set<String> roles) {
    }

    // ---- Deployment API -------------------------------------------------------------------------------------

    public Process deployProcess(Map<String, Object> request) {
        return deployment("POST", "/api/deployment/processes", request, Process.class);
    }

    public List<Process> processes() {
        return deployment("GET", "/api/deployment/processes", null,
                json.getTypeFactory().constructCollectionType(List.class, Process.class));
    }

    public Optional<Process> process(String key) {
        return optional(() -> deployment("GET", "/api/deployment/processes/" + encode(key), null, Process.class));
    }

    public ProcessVersion processVersion(String key, Integer version) {
        String path = "/api/deployment/processes/" + encode(key)
                + (version == null ? "/content" : "/versions/" + version);
        return deployment("GET", path, null, ProcessVersion.class);
    }

    public Process suspend(String key) {
        return deployment("POST", "/api/deployment/processes/" + encode(key) + "/suspend", null, Process.class);
    }

    public Process resume(String key) {
        return deployment("POST", "/api/deployment/processes/" + encode(key) + "/resume", null, Process.class);
    }

    public void deleteProcess(String key) {
        deployment("DELETE", "/api/deployment/processes/" + encode(key), null, Void.class);
    }

    public EventDeployment deployEvents(Map<String, Object> request) {
        return deployment("POST", "/api/deployment/events", request, EventDeployment.class);
    }

    public Optional<Model> event(String key) {
        return optional(() -> deployment("GET", "/api/deployment/events/" + encode(key), null, Model.class));
    }

    public Optional<Model> channel(String key) {
        return optional(() -> deployment("GET", "/api/deployment/channels/" + encode(key), null, Model.class));
    }

    public void deleteEvent(String key) {
        deployment("DELETE", "/api/deployment/events/" + encode(key), null, Void.class);
    }

    public void deleteChannel(String key) {
        deployment("DELETE", "/api/deployment/channels/" + encode(key), null, Void.class);
    }

    // ---- Admin API (as the user) ------------------------------------------------------------------------------

    /** Who {@code accessToken} is in {@code tenantCode} (null: the user's default tenant). */
    public Me me(String accessToken, String tenantCode) {
        HttpRequest.Builder request = request("/api/admin/me").GET()
                .header("Authorization", "Bearer " + accessToken);
        if (tenantCode != null && !tenantCode.isBlank()) {
            request.header(TENANT_HEADER, tenantCode);
        }
        return send(request.build(), json.constructType(Me.class));
    }

    /** {@code PUT /api/admin/service-environments/{key}} as the current user, in the current tenant. */
    public void saveServiceEnvironment(String serviceKey, Map<String, Object> settings) {
        byte[] body = write(settings);
        send(asUser("/api/admin/service-environments/" + encode(serviceKey))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .build(), json.constructType(Void.class));
    }

    /** Whether the service already has settings on Flow Engine ({@code GET /api/admin/service-environments/{key}}). */
    public boolean hasServiceEnvironment(String serviceKey) {
        return optional(() -> {
            send(asUser("/api/admin/service-environments/" + encode(serviceKey)).GET().build(),
                    json.constructType(Void.class));
            return Boolean.TRUE;
        }).isPresent();
    }

    /** Admin API request carrying the current user's token and tenant. */
    private HttpRequest.Builder asUser(String path) {
        String token = UserToken.current();
        if (token == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Missing user token");
        }
        return request(path)
                .header("Authorization", "Bearer " + token)
                .header(TENANT_HEADER, TenantContext.current().tenantCode());
    }

    // ---- plumbing ---------------------------------------------------------------------------------------------

    private <T> T deployment(String method, String path, Object body, Class<T> type) {
        return deployment(method, path, body, json.constructType(type));
    }

    private <T> T deployment(String method, String path, Object body, JavaType type) {
        byte[] bytes = body == null ? new byte[0] : write(body);
        HttpRequest.Builder request = request(path)
                .header(TOKEN_HEADER, serviceToken(method, path, bytes))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(bytes));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        CurrentPrincipal principal = CurrentPrincipalContext.currentOrNull();
        if (principal != null && principal.username() != null) {
            request.header(ACTING_USER_HEADER, principal.username());
        }
        return send(request.build(), type);
    }

    private HttpRequest.Builder request(String path) {
        if (properties.baseUrl().isBlank()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Flow Engine URL is not configured");
        }
        return HttpRequest.newBuilder(URI.create(properties.baseUrl() + path))
                .timeout(properties.readTimeout())
                .header("Accept", "application/json");
    }

    private <T> T send(HttpRequest request, JavaType type) {
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.WORKFLOW_ERROR, "Không kết nối được Flow Engine");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.WORKFLOW_ERROR, "Không kết nối được Flow Engine");
        }
        JsonNode envelope = readTree(response.body());
        if (response.statusCode() >= 300 || envelope == null
                || !"SUCCESS".equals(envelope.path("code").asText())) {
            throw error(response.statusCode(), envelope);
        }
        JsonNode data = envelope.get("data");
        if (data == null || data.isNull() || type.getRawClass() == Void.class) {
            return null;
        }
        return json.convertValue(data, type);
    }

    private static BusinessException error(int status, JsonNode envelope) {
        String code = envelope == null ? null : envelope.path("code").asText(null);
        String message = envelope == null ? null : envelope.path("message").asText(null);
        ErrorCode errorCode = Arrays.stream(ErrorCode.values())
                .filter(candidate -> candidate.code().equals(code))
                .findFirst()
                .orElseGet(() -> switch (status) {
                    case 400 -> ErrorCode.BAD_REQUEST;
                    case 401 -> ErrorCode.UNAUTHORIZED;
                    case 403 -> ErrorCode.FORBIDDEN;
                    case 404 -> ErrorCode.NOT_FOUND;
                    case 409 -> ErrorCode.CONFLICT;
                    default -> ErrorCode.WORKFLOW_ERROR;
                });
        return new BusinessException(errorCode, message == null || message.isBlank()
                ? "Flow Engine trả lỗi " + status : message);
    }

    private static <T> Optional<T> optional(java.util.function.Supplier<T> call) {
        try {
            return Optional.ofNullable(call.get());
        } catch (BusinessException exception) {
            if (exception.getErrorCode() == ErrorCode.NOT_FOUND) {
                return Optional.empty();
            }
            throw exception;
        }
    }

    /**
     * {@code clientCode.base64url(iv || AES-GCM(sha256(secret), payload))}, the format Flow Engine's
     * {@code ServiceTokenCryptoService} reads. The payload binds the token to this method, path and body.
     */
    String serviceToken(String method, String path, byte[] body) {
        if (properties.clientCode() == null || properties.clientSecret() == null
                || properties.clientSecret().isBlank()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Flow Engine service client is not configured");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientCode", properties.clientCode());
        payload.put("tenantCode", TenantContext.current().tenantCode());
        payload.put("timestamp", Instant.now().getEpochSecond());
        payload.put("nonce", UUID.randomUUID().toString());
        payload.put("method", method);
        payload.put("path", path);
        payload.put("bodySha256", HexFormat.of().formatHex(sha256(body)));
        payload.put("tokenVersion", properties.tokenVersion());
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(sha256(properties.clientSecret().getBytes(StandardCharsets.UTF_8)), "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(write(payload));
            byte[] token = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
            return properties.clientCode() + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to sign the Flow Engine request", exception);
        }
    }

    private byte[] write(Object value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write the Flow Engine request", exception);
        }
    }

    private JsonNode readTree(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return json.readTree(body);
        } catch (IOException exception) {
            return null;
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
