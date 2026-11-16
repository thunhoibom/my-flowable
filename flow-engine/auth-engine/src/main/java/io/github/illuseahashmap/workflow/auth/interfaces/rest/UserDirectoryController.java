package io.github.illuseahashmap.workflow.auth.interfaces.rest;

import io.github.illuseahashmap.workflow.auth.application.UserDirectoryService;
import io.github.illuseahashmap.workflow.auth.application.dto.DirectoryUserView;
import io.github.illuseahashmap.workflow.shared.context.ApiRoles;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Runtime API: users of the current tenant, to hand a task to (Work area). */
@RestController
@RequestMapping("/api/runtime/directory")
public class UserDirectoryController {

    private final UserDirectoryService userDirectoryService;

    public UserDirectoryController(UserDirectoryService userDirectoryService) {
        this.userDirectoryService = userDirectoryService;
    }

    @GetMapping("/users")
    @PreAuthorize(ApiRoles.OPERATE)
    public ApiResponse<PageResult<DirectoryUserView>> users(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.ok(userDirectoryService.search(keyword, pageNum, pageSize));
    }
}
