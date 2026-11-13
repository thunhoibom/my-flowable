package io.github.illuseahashmap.designhub.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.illuseahashmap.designhub.engine.FlowEngineClient;
import io.github.illuseahashmap.designhub.engine.FlowEngineProperties;
import io.github.illuseahashmap.designhub.engine.UserToken;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalContext;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class EngineAuthenticationFilterTest {

    private final List<String> asked = new ArrayList<>();
    private boolean refuse;

    private final FlowEngineClient engine = new FlowEngineClient(new FlowEngineProperties("http://unused",
            "flow-admin-be", "s", 1, null, null, null, null)) {
        @Override
        public Me me(String accessToken, String tenantCode) {
            asked.add(accessToken + "|" + tenantCode);
            if (refuse) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "Tenant membership does not exist");
            }
            return new Me("USER", "u1", "alice", "Alice", "tenant-1", "t1", "T1", Set.of("WF_ADMIN"));
        }
    };

    private final EngineAuthenticationFilter filter = new EngineAuthenticationFilter(engine, "session-cookie",
            Duration.ofSeconds(60), Clock.fixed(Instant.parse("2026-11-13T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void asksFlowEngineOnceAndSetsTheUserTenantRolesAndToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/services");
        request.addHeader("Authorization", "Bearer user-token");
        request.addHeader("X-Tenant-Code", "t1");
        List<String> seen = new ArrayList<>();
        MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req,
                                   jakarta.servlet.http.HttpServletResponse resp) {
                seen.add(CurrentPrincipalContext.current().username());
                seen.add(TenantContext.current().tenantId());
                seen.add(UserToken.current());
                seen.add(SecurityContextHolder.getContext().getAuthentication().getAuthorities().toString());
            }
        });

        filter.doFilter(request, new MockHttpServletResponse(), chain);
        MockHttpServletRequest again = new MockHttpServletRequest("GET", "/services");
        again.addHeader("Authorization", "Bearer user-token");
        again.addHeader("X-Tenant-Code", "t1");
        filter.doFilter(again, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(seen).containsExactly("alice", "tenant-1", "user-token", "[ROLE_WF_ADMIN]");
        assertThat(asked).containsExactly("user-token|t1");
        assertThat(CurrentPrincipalContext.currentOrNull()).isNull();
        assertThat(UserToken.current()).isNull();
    }

    @Test
    void readsTheSessionCookieAndReportsARefusal() throws Exception {
        refuse = true;
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/services");
        request.setCookies(new Cookie("session-cookie", "cookie-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(asked).containsExactly("cookie-token|null");
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("FORBIDDEN");
    }

    @Test
    void letsRequestsWithoutTokenThroughUntouched() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET", "/services"), new MockHttpServletResponse(), chain);

        assertThat(asked).isEmpty();
        assertThat(chain.getRequest()).isNotNull();
    }
}
