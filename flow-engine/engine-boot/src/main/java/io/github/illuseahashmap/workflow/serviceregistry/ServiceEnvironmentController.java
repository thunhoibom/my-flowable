package io.github.illuseahashmap.workflow.serviceregistry;

import io.github.illuseahashmap.serviceregistry.domain.ServiceSettings;
import io.github.illuseahashmap.serviceregistry.infrastructure.JdbcServiceEnvironmentStore;
import io.github.illuseahashmap.workflow.process.application.WorkflowOperationAuditService;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API: base URL and credentials of each service on this Flow Engine ({@code work_service_environment}).
 * Hub edits them later; Design never sees a secret. Responses carry no secret, only which ones are stored.
 * A secret left null in a save keeps the stored value.
 */
@RestController
@RequestMapping("/api/admin/service-environments")
@PreAuthorize("hasAnyRole('WF_PLATFORM_ADMIN', 'WF_ADMIN')")
public class ServiceEnvironmentController {

    private static final Pattern SERVICE_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,127}");

    private final JdbcServiceEnvironmentStore store;
    private final TenantProvider tenants;
    private final CurrentPrincipalProvider principals;
    private final WorkflowOperationAuditService auditService;

    public ServiceEnvironmentController(JdbcServiceEnvironmentStore store, TenantProvider tenants,
                                        CurrentPrincipalProvider principals,
                                        WorkflowOperationAuditService auditService) {
        this.store = store;
        this.tenants = tenants;
        this.principals = principals;
        this.auditService = auditService;
    }

    @GetMapping
    public ApiResponse<List<JdbcServiceEnvironmentStore.Entry>> list() {
        return ApiResponse.ok(store.list(tenants.current().tenantId()));
    }

    @GetMapping("/{serviceKey}")
    public ApiResponse<JdbcServiceEnvironmentStore.Entry> get(@PathVariable String serviceKey) {
        return ApiResponse.ok(store.entry(tenants.current().tenantId(), serviceKey).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Dịch vụ " + serviceKey + " chưa có cấu hình môi trường")));
    }

    @PutMapping("/{serviceKey}")
    @Transactional(rollbackFor = Exception.class)
    public ApiResponse<JdbcServiceEnvironmentStore.Entry> save(@PathVariable String serviceKey,
                                                              @RequestBody ServiceSettings settings) {
        if (!SERVICE_KEY.matcher(serviceKey).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "serviceKey không hợp lệ");
        }
        String baseUrl = settings.baseUrl();
        if (StringUtils.hasText(baseUrl) && !baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "baseUrl phải bắt đầu bằng http:// hoặc https://");
        }
        String tenantId = tenants.current().tenantId();
        store.save(tenantId, serviceKey, settings, actor());
        auditService.record("SERVICE_ENVIRONMENT_SAVED", null, null, null, serviceKey, null, "SAVED", null);
        return ApiResponse.ok(store.entry(tenantId, serviceKey).orElseThrow());
    }

    @DeleteMapping("/{serviceKey}")
    @Transactional(rollbackFor = Exception.class)
    public ApiResponse<Void> delete(@PathVariable String serviceKey) {
        if (!store.delete(tenants.current().tenantId(), serviceKey)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Dịch vụ " + serviceKey + " chưa có cấu hình môi trường");
        }
        auditService.record("SERVICE_ENVIRONMENT_DELETED", null, null, null, serviceKey, "SAVED", "DELETED", null);
        return ApiResponse.ok();
    }

    private String actor() {
        CurrentPrincipal principal = principals.current();
        return StringUtils.hasText(principal.username()) ? principal.username() : principal.principalId();
    }
}
