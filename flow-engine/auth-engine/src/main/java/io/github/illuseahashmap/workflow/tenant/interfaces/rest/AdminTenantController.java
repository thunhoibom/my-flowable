package io.github.illuseahashmap.workflow.tenant.interfaces.rest;

import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import io.github.illuseahashmap.workflow.tenant.application.TenantManagementService;
import io.github.illuseahashmap.workflow.tenant.application.dto.TenantCommand;
import io.github.illuseahashmap.workflow.tenant.application.dto.TenantView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin API: tenants. Platform administrators only. */
@RestController
@RequestMapping("/api/admin/tenants")
@PreAuthorize("hasRole('WF_PLATFORM_ADMIN')")
public class AdminTenantController {

    private final TenantManagementService tenants;

    public AdminTenantController(TenantManagementService tenants) {
        this.tenants = tenants;
    }

    @GetMapping
    public ApiResponse<PageResult<TenantView>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                                    @RequestParam(defaultValue = "20") Integer pageSize,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) Boolean enabled) {
        return ApiResponse.ok(tenants.page(pageNum, pageSize, keyword, enabled));
    }

    @GetMapping("/enabled")
    public ApiResponse<List<TenantView>> listEnabled() {
        return ApiResponse.ok(tenants.listEnabled());
    }

    @PostMapping
    public ApiResponse<TenantView> create(@Valid @RequestBody TenantCommand command) {
        return ApiResponse.ok(tenants.create(command));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable long id, @Valid @RequestBody TenantCommand command) {
        tenants.update(id, command);
        return ApiResponse.ok();
    }

    @PutMapping("/{id}/enabled")
    public ApiResponse<Void> updateEnabled(@PathVariable long id, @RequestParam boolean enabled) {
        tenants.updateEnabled(id, enabled);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<Void> restore(@PathVariable long id) {
        tenants.restore(id);
        return ApiResponse.ok();
    }
}
