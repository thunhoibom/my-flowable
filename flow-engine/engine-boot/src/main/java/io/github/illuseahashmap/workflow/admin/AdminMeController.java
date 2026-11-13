package io.github.illuseahashmap.workflow.admin;

import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API: who the caller is in the tenant it asked for ({@code X-Tenant-Code}). Flow Admin BE authenticates its
 * users with this: it forwards the user token, and Flow Engine checks the token, the roles and the membership.
 */
@RestController
public class AdminMeController {

    private final CurrentPrincipalProvider principals;
    private final TenantProvider tenants;

    public AdminMeController(CurrentPrincipalProvider principals, TenantProvider tenants) {
        this.principals = principals;
        this.tenants = tenants;
    }

    @GetMapping("/api/admin/me")
    public ApiResponse<Me> me() {
        CurrentPrincipal principal = principals.current();
        TenantContext.TenantInfo tenant = tenants.current();
        return ApiResponse.ok(new Me(principal.principalType(), principal.principalId(), principal.username(),
                principal.displayName(), tenant.tenantId(), tenant.tenantCode(), tenant.tenantName(),
                principal.roles()));
    }

    /** The caller: user (or service client), tenant, and EIM roles. */
    public record Me(String principalType, String userId, String username, String displayName, String tenantId,
                     String tenantCode, String tenantName, Set<String> roles) {
    }
}
