package io.github.illuseahashmap.workflow.serviceregistry;

import io.github.illuseahashmap.serviceregistry.infrastructure.JdbcTenantVariableStore;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API: tenant variables of this Flow Engine ({@code work_tenant_variable}), like Flowable Hub
 * "Tenant Variables". Models read them as {@code ${tenant.<name>}}. A protected value is returned masked; a save
 * of a protected variable without a value keeps the stored one; protection cannot be removed.
 */
@RestController
@RequestMapping("/api/admin/tenant-variables")
@PreAuthorize("hasAnyRole('WF_PLATFORM_ADMIN', 'WF_ADMIN')")
public class TenantVariableController {

    private final JdbcTenantVariableStore store;
    private final TenantProvider tenants;
    private final CurrentPrincipalProvider principals;
    private final WorkflowOperationAuditService auditService;

    public TenantVariableController(JdbcTenantVariableStore store, TenantProvider tenants,
                                    CurrentPrincipalProvider principals, WorkflowOperationAuditService auditService) {
        this.store = store;
        this.tenants = tenants;
        this.principals = principals;
        this.auditService = auditService;
    }

    /** {@code value} null keeps the stored value of a protected variable. */
    public record SaveTenantVariableRequest(String value, Boolean protectedValue) {

        boolean isProtected() {
            return Boolean.TRUE.equals(protectedValue);
        }
    }

    @GetMapping
    public ApiResponse<List<JdbcTenantVariableStore.Entry>> list() {
        return ApiResponse.ok(store.list(tenants.current().tenantId()));
    }

    @PutMapping("/{name}")
    @Transactional(rollbackFor = Exception.class)
    public ApiResponse<JdbcTenantVariableStore.Entry> save(@PathVariable String name,
                                                          @RequestBody SaveTenantVariableRequest request) {
        ServiceConfigNames.requireIdentifier(name, "name");
        String tenantId = tenants.current().tenantId();
        if (!request.isProtected() && store.find(tenantId, name)
                .map(JdbcTenantVariableStore.Entry::protectedValue).orElse(false)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Biến đã bảo vệ thì không bỏ bảo vệ được");
        }
        try {
            store.save(tenantId, name, request.value(), request.isProtected(), ServiceConfigNames.actor(principals));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "value là bắt buộc");
        }
        auditService.record("TENANT_VARIABLE_SAVED", null, null, null, name, null, "SAVED", null);
        return ApiResponse.ok(store.find(tenantId, name).orElseThrow());
    }

    @DeleteMapping("/{name}")
    @Transactional(rollbackFor = Exception.class)
    public ApiResponse<Void> delete(@PathVariable String name) {
        if (!store.delete(tenants.current().tenantId(), name)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Không có biến tenant " + name);
        }
        auditService.record("TENANT_VARIABLE_DELETED", null, null, null, name, "SAVED", "DELETED", null);
        return ApiResponse.ok();
    }
}
