package io.github.illuseahashmap.workflow.serviceregistry;

import io.github.illuseahashmap.serviceregistry.infrastructure.JdbcSecretStore;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API: secrets of this Flow Engine ({@code work_secret}), like Flowable Hub "Secrets". A value is sent once,
 * at creation, and never returned. There is no update: delete the secret and create it again.
 */
@RestController
@RequestMapping("/api/admin/secrets")
@PreAuthorize("hasAnyRole('WF_PLATFORM_ADMIN', 'WF_ADMIN')")
public class SecretController {

    private final JdbcSecretStore store;
    private final TenantProvider tenants;
    private final CurrentPrincipalProvider principals;
    private final WorkflowOperationAuditService auditService;

    public SecretController(JdbcSecretStore store, TenantProvider tenants, CurrentPrincipalProvider principals,
                            WorkflowOperationAuditService auditService) {
        this.store = store;
        this.tenants = tenants;
        this.principals = principals;
        this.auditService = auditService;
    }

    public record CreateSecretRequest(String name, String value) {
    }

    @GetMapping
    public ApiResponse<List<JdbcSecretStore.Entry>> list() {
        return ApiResponse.ok(store.list(tenants.current().tenantId()));
    }

    @PostMapping
    @Transactional(rollbackFor = Exception.class)
    public ApiResponse<Void> create(@RequestBody CreateSecretRequest request) {
        String name = ServiceConfigNames.requireKey(request.name(), "name");
        if (!StringUtils.hasLength(request.value())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "value là bắt buộc");
        }
        if (!store.create(tenants.current().tenantId(), name, request.value(), ServiceConfigNames.actor(principals))) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Secret " + name + " đã có. Muốn đổi giá trị thì xoá rồi tạo lại");
        }
        auditService.record("SECRET_CREATED", null, null, null, name, null, "CREATED", null);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{name}")
    @Transactional(rollbackFor = Exception.class)
    public ApiResponse<Void> delete(@PathVariable String name) {
        if (!store.delete(tenants.current().tenantId(), name)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Không có secret " + name);
        }
        auditService.record("SECRET_DELETED", null, null, null, name, "CREATED", "DELETED", null);
        return ApiResponse.ok();
    }
}
