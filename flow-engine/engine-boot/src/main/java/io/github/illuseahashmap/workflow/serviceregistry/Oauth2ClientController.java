package io.github.illuseahashmap.workflow.serviceregistry;

import io.github.illuseahashmap.serviceregistry.infrastructure.JdbcOauth2ClientStore;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API: OAuth2 clients of this Flow Engine ({@code work_oauth2_client}), like Flowable Hub "OAuth2 Clients".
 * Service models reference a client by registration key. The client secret is never returned; a save without one
 * keeps the stored secret. Only the client credentials grant is supported.
 */
@RestController
@RequestMapping("/api/admin/oauth2-clients")
@PreAuthorize("hasAnyRole('WF_PLATFORM_ADMIN', 'WF_ADMIN')")
public class Oauth2ClientController {

    private final JdbcOauth2ClientStore store;
    private final TenantProvider tenants;
    private final CurrentPrincipalProvider principals;
    private final WorkflowOperationAuditService auditService;

    public Oauth2ClientController(JdbcOauth2ClientStore store, TenantProvider tenants,
                                  CurrentPrincipalProvider principals, WorkflowOperationAuditService auditService) {
        this.store = store;
        this.tenants = tenants;
        this.principals = principals;
        this.auditService = auditService;
    }

    @GetMapping
    public ApiResponse<List<JdbcOauth2ClientStore.Entry>> list() {
        return ApiResponse.ok(store.list(tenants.current().tenantId()));
    }

    @GetMapping("/{registrationKey}")
    public ApiResponse<JdbcOauth2ClientStore.Entry> get(@PathVariable String registrationKey) {
        return ApiResponse.ok(store.find(tenants.current().tenantId(), registrationKey).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "Không có OAuth2 client " + registrationKey)));
    }

    @PutMapping("/{registrationKey}")
    @Transactional(rollbackFor = Exception.class)
    public ApiResponse<JdbcOauth2ClientStore.Entry> save(@PathVariable String registrationKey,
                                                        @RequestBody JdbcOauth2ClientStore.Input input) {
        ServiceConfigNames.requireKey(registrationKey, "registrationKey");
        String tokenUri = input.tokenUri();
        if (!StringUtils.hasText(tokenUri) || !(tokenUri.startsWith("https://") || tokenUri.startsWith("http://"))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "tokenUri phải bắt đầu bằng http:// hoặc https://");
        }
        if (input.grantType() != null && !JdbcOauth2ClientStore.CLIENT_CREDENTIALS.equals(input.grantType())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Chỉ hỗ trợ grant type CLIENT_CREDENTIALS");
        }
        // Flowable defaults the client id to the registration key.
        String clientId = StringUtils.hasText(input.clientId()) ? input.clientId().trim() : registrationKey;
        String tenantId = tenants.current().tenantId();
        try {
            store.save(tenantId, registrationKey, new JdbcOauth2ClientStore.Input(clientId, input.clientSecret(),
                    tokenUri.trim(), input.scopes(), JdbcOauth2ClientStore.CLIENT_CREDENTIALS),
                    ServiceConfigNames.actor(principals));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "clientSecret là bắt buộc khi tạo mới");
        }
        auditService.record("OAUTH2_CLIENT_SAVED", null, null, null, registrationKey, null, "SAVED", null);
        return ApiResponse.ok(store.find(tenantId, registrationKey).orElseThrow());
    }

    @DeleteMapping("/{registrationKey}")
    @Transactional(rollbackFor = Exception.class)
    public ApiResponse<Void> delete(@PathVariable String registrationKey) {
        if (!store.delete(tenants.current().tenantId(), registrationKey)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Không có OAuth2 client " + registrationKey);
        }
        auditService.record("OAUTH2_CLIENT_DELETED", null, null, null, registrationKey, "SAVED", "DELETED", null);
        return ApiResponse.ok();
    }
}
