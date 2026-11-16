package io.github.illuseahashmap.workflow.auth.interfaces.rest;

import io.github.illuseahashmap.workflow.auth.application.AccessManagementService;
import io.github.illuseahashmap.workflow.auth.application.UserDirectoryService;
import io.github.illuseahashmap.workflow.auth.application.dto.AddTenantMemberRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.DirectoryUserView;
import io.github.illuseahashmap.workflow.auth.application.dto.TenantMemberView;
import io.github.illuseahashmap.workflow.shared.context.ApiRoles;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
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

/** Admin API: members of the current tenant (roles are assigned in EIM), and the user directory to pick from. */
@RestController
@RequestMapping("/api/admin")
public class AdminMemberController {

    private final AccessManagementService access;
    private final UserDirectoryService directory;

    public AdminMemberController(AccessManagementService access, UserDirectoryService directory) {
        this.access = access;
        this.directory = directory;
    }

    @GetMapping("/members")
    @PreAuthorize("hasRole('WF_PLATFORM_ADMIN')")
    public ApiResponse<List<TenantMemberView>> members(@RequestParam(required = false) String keyword) {
        return ApiResponse.ok(access.members(keyword));
    }

    @PostMapping("/members")
    @PreAuthorize("hasRole('WF_PLATFORM_ADMIN')")
    public ApiResponse<TenantMemberView> addMember(@Valid @RequestBody AddTenantMemberRequest request) {
        return ApiResponse.ok(access.addMember(request));
    }

    @PutMapping("/members/{userId}/enabled")
    @PreAuthorize("hasRole('WF_PLATFORM_ADMIN')")
    public ApiResponse<Void> updateMemberEnabled(@PathVariable String userId, @RequestParam boolean enabled) {
        access.updateMemberEnabled(userId, enabled);
        return ApiResponse.ok();
    }

    /** Users to add as members or to hand a task to. */
    @GetMapping("/directory/users")
    @PreAuthorize(ApiRoles.OPERATE)
    public ApiResponse<PageResult<DirectoryUserView>> users(@RequestParam(required = false) String keyword,
                                                            @RequestParam(required = false) Integer pageNum,
                                                            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.ok(directory.search(keyword, pageNum, pageSize));
    }
}
