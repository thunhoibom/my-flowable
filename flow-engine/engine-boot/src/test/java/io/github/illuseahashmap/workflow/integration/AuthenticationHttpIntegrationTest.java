package io.github.illuseahashmap.workflow.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.illuseahashmap.workflow.WorkflowAgentServiceApplication;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full security chain against a fake Keycloak realm and EIM: SSO login, roles from the
 * {@code eim_roles} claim checked by {@code @PreAuthorize("hasAnyRole(...)")}, and the EIM menu.
 */
@SpringBootTest(classes = WorkflowAgentServiceApplication.class)
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthenticationHttpIntegrationTest {

    private static final String ADMIN_SITEMAP = """
            {"status":"1","sitemaps":[{"strCode":"WF_ROOT","strName":"Quy trình","strStatus":"1","ord":1,
              "privileges":[{"code":"ACCESS"}],
              "childrent":[{"strCode":"WF_SERVICE","strName":"Dịch vụ","strStatus":"1","ord":2,"privileges":[]},
                           {"strCode":"WF_DEFINITION","strName":"Quy trình","strStatus":"1","ord":1,"privileges":[]},
                           {"strCode":"WF_AGENT","strName":"Agent","strStatus":"0","ord":3,"privileges":[]}]}]}
            """;
    private static final String EMPTY_SITEMAP = "{\"status\":\"1\",\"sitemaps\":[]}";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final FakeKeycloak KEYCLOAK = new FakeKeycloak();

    static {
        KEYCLOAK.account("designer", "designer-password", "WF_ADMIN", ADMIN_SITEMAP);
        KEYCLOAK.account("viewer", "viewer-password", "WF_VIEWER", EMPTY_SITEMAP);
        KEYCLOAK.account("platform", "platform-password", "WF_PLATFORM_ADMIN", EMPTY_SITEMAP);
        KEYCLOAK.account("rsa-only", "rsa-password", "RSA_ADMIN", null);
        KEYCLOAK.account("protected-user", "protected-password", "WF_VIEWER", EMPTY_SITEMAP);
    }

    @Autowired
    private WebApplicationContext applicationContext;

    private MockMvc mockMvc;

    @DynamicPropertySource
    static void configureApplication(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Schema is managed by hand (flow-engine/db); tests apply the same scripts.
        DatabaseScripts.apply(POSTGRES);
        registry.add("flowable.database-schema-update", () -> "true");
        registry.add("flowable.async-executor-activate", () -> "false");
        registry.add("workflow.security.enabled", () -> "false");
        registry.add("workflow.auth.token.cookie-secure", () -> "false");
        registry.add("workflow.runtime.environment", () -> "test");
        registry.add("workflow.auth.protection.failure-threshold", () -> "3");
        registry.add("workflow.auth.protection.base-lock-seconds", () -> "1");
        registry.add("workflow.auth.keycloak.server-url", KEYCLOAK::serverUrl);
        registry.add("workflow.auth.keycloak.realm", () -> "rsa");
        registry.add("workflow.auth.keycloak.client-id", () -> FakeKeycloak.CLIENT_ID);
        registry.add("workflow.auth.keycloak.client-secret", () -> "test-secret");
        registry.add("workflow.auth.keycloak.allowed-client-ids", () -> FakeKeycloak.CLIENT_ID);
        registry.add("workflow.auth.eim.app-code", () -> FakeKeycloak.CLIENT_ID);
        registry.add("workflow.service.secrets.master-key-base64", () -> java.util.Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @AfterAll
    static void stopKeycloak() {
        KEYCLOAK.stop();
    }

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).apply(springSecurity()).build();
    }

    @Test
    void ssoLoginSetsCookiesAndRolesComeFromEimRoles() throws Exception {
        MvcResult login = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"designer","password":"designer-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(jsonPath("$.data.tenantCode").value("default"))
                .andExpect(jsonPath("$.data.roles[0]").value("WF_ADMIN"))
                .andReturn();
        Cookie accessCookie = login.getResponse().getCookie("workflow-agent.access-token");
        Cookie refreshCookie = login.getResponse().getCookie("workflow-agent.refresh-token");
        org.assertj.core.api.Assertions.assertThat(accessCookie).isNotNull();
        org.assertj.core.api.Assertions.assertThat(refreshCookie).isNotNull();

        mockMvc.perform(get("/auth/me").cookie(accessCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("designer"))
                .andExpect(jsonPath("$.data.roles[0]").value("WF_ADMIN"));
        mockMvc.perform(get("/api/admin/secrets").cookie(accessCookie))
                .andExpect(status().isOk());
    }

    @Test
    void endpointsAcceptOnlyTheRolesTheyList() throws Exception {
        mockMvc.perform(get("/api/admin/secrets").with(bearer("viewer")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/members").with(bearer("designer")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/members").with(bearer("platform")))
                .andExpect(status().isOk());
        // Signed in to the realm but no workflow role: nothing is allowed.
        mockMvc.perform(get("/api/admin/secrets").with(bearer("rsa-only")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminApiChecksTheUserRoles() throws Exception {
        mockMvc.perform(get("/api/admin/process-instances").with(bearer("viewer")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/failed-jobs").with(bearer("viewer")))
                .andExpect(status().isOk());
        // A bearer token is not sent cross-site, so Admin API writes need no CSRF token; the role still applies.
        mockMvc.perform(post("/api/admin/failed-jobs/missing/retry").with(bearer("viewer")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/failed-jobs/missing/retry").with(bearer("designer")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/admin/members").with(bearer("designer")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/members").with(bearer("platform")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/tenants").with(bearer("platform")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/process-instances").with(bearer("rsa-only")))
                .andExpect(status().isForbidden());
        // Runtime API reads for the Work area.
        mockMvc.perform(get("/api/runtime/tasks/inbox").with(bearer("viewer")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/runtime/process-definitions/startable").with(bearer("viewer")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/runtime/process-definitions/startable").with(bearer("designer")))
                .andExpect(status().isOk());
    }

    @Test
    void menuComesFromTheEimSitemap() throws Exception {
        mockMvc.perform(get("/auth/sitemaps").with(bearer("designer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("WF_ROOT"))
                .andExpect(jsonPath("$.data[0].children.length()").value(2))
                .andExpect(jsonPath("$.data[0].children[0].code").value("WF_DEFINITION"))
                .andExpect(jsonPath("$.data[0].children[1].code").value("WF_SERVICE"));
    }

    @Test
    void eimOutageOnlyAffectsTheMenu() throws Exception {
        mockMvc.perform(get("/auth/sitemaps").with(bearer("rsa-only")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EIM_UNAVAILABLE"));
        mockMvc.perform(get("/auth/me").with(bearer("rsa-only")))
                .andExpect(status().isOk());
    }

    @Test
    void localRegistrationNoLongerExists() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"someone","password":"some-password"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void repeatedLoginFailuresAreTemporarilyBlocked() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(post("/auth/login")
                            .with(protectedSource())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"protected-user","password":"wrong-password"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/auth/login")
                        .with(protectedSource())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"protected-user","password":"protected-password"}
                                """))
                .andExpect(status().isTooManyRequests());

        Thread.sleep(1100);

        mockMvc.perform(post("/auth/login")
                        .with(protectedSource())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"protected-user","password":"protected-password"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void secretsOauth2ClientsAndTenantVariablesNeverReturnSecretValues() throws Exception {
        mockMvc.perform(post("/api/admin/secrets").with(bearer("designer")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"crmToken","value":"top-secret-1"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/secrets").with(bearer("designer")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"crmToken","value":"again"}
                                """))
                .andExpect(status().isConflict());
        String secrets = mockMvc.perform(get("/api/admin/secrets").with(bearer("designer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("crmToken"))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(secrets).doesNotContain("top-secret-1");
        mockMvc.perform(post("/api/admin/secrets").with(bearer("viewer")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"other","value":"x"}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/admin/oauth2-clients/crm-oauth").with(bearer("designer"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tokenUri":"https://idp.example/token","scopes":["crm"]}
                                """))
                .andExpect(status().isBadRequest());
        String client = mockMvc.perform(put("/api/admin/oauth2-clients/crm-oauth").with(bearer("designer"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientSecret":"client-secret-1","tokenUri":"https://idp.example/token","scopes":["crm"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clientId").value("crm-oauth"))
                .andExpect(jsonPath("$.data.grantType").value("CLIENT_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(client).doesNotContain("client-secret-1");

        mockMvc.perform(put("/api/admin/tenant-variables/crm-url").with(bearer("designer"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"value":"https://crm.dev"}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/admin/tenant-variables/crmPassword").with(bearer("designer"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"value":"p@ss","protectedValue":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.value").value("******"));
        mockMvc.perform(put("/api/admin/tenant-variables/crmPassword").with(bearer("designer"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"value":"plain","protectedValue":false}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/admin/secrets/crmToken").with(bearer("designer")))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/admin/oauth2-clients/crm-oauth").with(bearer("designer")))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/admin/tenant-variables/crmPassword").with(bearer("designer")))
                .andExpect(status().isOk());
    }

    private static RequestPostProcessor bearer(String username) {
        String token = KEYCLOAK.accessToken(username);
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }

    private RequestPostProcessor protectedSource() {
        return request -> {
            request.setRemoteAddr("198.51.100.10");
            return request;
        };
    }
}
