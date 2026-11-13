package io.github.illuseahashmap.designhub.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.designhub.engine.FlowEngineClient;
import io.github.illuseahashmap.designhub.engine.UserToken;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalContext;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates users of Flow Admin BE by asking Flow Engine ({@code GET /api/admin/me}) with the user's own access
 * token (Bearer header or the session cookie Flow Engine set) and {@code X-Tenant-Code}. Flow Engine checks the
 * Keycloak token, the EIM roles and the tenant membership; the answer is cached for a short time per token and
 * tenant. Roles become {@code ROLE_<code>} as in Flow Engine.
 */
public class EngineAuthenticationFilter extends OncePerRequestFilter {

    static final String TENANT_HEADER = "X-Tenant-Code";
    private static final String BEARER = "Bearer ";
    private static final int MAX_CACHE_ENTRIES = 10_000;
    private static final Set<String> PUBLIC_PATHS = Set.of("/actuator/health", "/actuator/info");

    private final FlowEngineClient engine;
    private final String cookieName;
    private final Duration cacheTtl;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public EngineAuthenticationFilter(FlowEngineClient engine, String cookieName, Duration cacheTtl, Clock clock) {
        this.engine = engine;
        this.cookieName = cookieName;
        this.cacheTtl = cacheTtl;
        this.clock = clock;
    }

    private record Cached(FlowEngineClient.Me me, Instant expiresAt) {
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod()) || PUBLIC_PATHS.contains(request.getRequestURI())
                || token(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = token(request);
        FlowEngineClient.Me me;
        try {
            me = me(token, request.getHeader(TENANT_HEADER));
        } catch (BusinessException exception) {
            writeError(response, exception);
            return;
        }
        try {
            CurrentPrincipal principal = new CurrentPrincipal("USER", me.userId(), me.username(), me.displayName(),
                    me.tenantCode(), me.roles() == null ? Set.of() : me.roles(), Set.of());
            CurrentPrincipalContext.set(principal);
            TenantContext.set(new TenantContext.TenantInfo(me.tenantId(), me.tenantCode(), me.tenantName()));
            UserToken.set(token);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    principal, null, principal.roles().stream()
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList()));
            chain.doFilter(request, response);
        } finally {
            CurrentPrincipalContext.clear();
            TenantContext.clear();
            UserToken.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private FlowEngineClient.Me me(String token, String tenantCode) {
        String key = sha256(token) + "|" + (tenantCode == null ? "" : tenantCode.trim());
        Instant now = clock.instant();
        Cached cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.me();
        }
        FlowEngineClient.Me me = engine.me(token, tenantCode);
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
        cache.put(key, new Cached(me, now.plus(cacheTtl)));
        return me;
    }

    private String token(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith(BEARER)) {
            return authorization.substring(BEARER.length());
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookieName.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    private void writeError(HttpServletResponse response, BusinessException exception) throws IOException {
        int status = switch (exception.getErrorCode()) {
            case UNAUTHORIZED -> HttpServletResponse.SC_UNAUTHORIZED;
            case FORBIDDEN -> HttpServletResponse.SC_FORBIDDEN;
            default -> HttpServletResponse.SC_SERVICE_UNAVAILABLE;
        };
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(json.writeValueAsString(
                ApiResponse.fail(exception.getErrorCode().code(), exception.getMessage())));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
