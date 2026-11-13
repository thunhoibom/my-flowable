package io.github.illuseahashmap.designhub.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.designhub.engine.FlowEngineClient;
import io.github.illuseahashmap.designhub.engine.FlowEngineProperties;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalContext;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

/**
 * Same rules as Flow Engine for the browser: the session cookie Flow Engine set is accepted, so every unsafe
 * request must carry the {@code X-XSRF-TOKEN} header matching the {@code XSRF-TOKEN} cookie. A request with a Bearer
 * token cannot be forged cross-site and skips that check.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Bean
    public CurrentPrincipalProvider currentPrincipalProvider() {
        return CurrentPrincipalContext::current;
    }

    @Bean
    public TenantProvider tenantProvider() {
        return TenantContext::current;
    }

    @Bean
    public EngineAuthenticationFilter engineAuthenticationFilter(
            FlowEngineClient engine, FlowEngineProperties properties,
            @Value("${flow-admin.auth.access-token-cookie:workflow-agent.access-token}") String cookieName) {
        return new EngineAuthenticationFilter(engine, cookieName, properties.authCacheTtl(), Clock.systemUTC());
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, EngineAuthenticationFilter authenticationFilter)
            throws Exception {
        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers(request -> StringUtils.startsWithIgnoreCase(
                                request.getHeader(HttpHeaders.AUTHORIZATION), "Bearer ")))
                .sessionManagement(configurer -> configurer.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(registry -> registry
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, exception) ->
                                write(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, exception) ->
                                write(response, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN)))
                .addFilterBefore(authenticationFilter, CsrfFilter.class)
                .build();
    }

    private static void write(HttpServletResponse response, int status, ErrorCode code) throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSON.writeValueAsString(ApiResponse.fail(code.code(), code.defaultMessage())));
    }
}
