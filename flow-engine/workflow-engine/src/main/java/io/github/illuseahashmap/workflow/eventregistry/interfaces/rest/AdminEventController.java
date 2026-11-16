package io.github.illuseahashmap.workflow.eventregistry.interfaces.rest;

import io.github.illuseahashmap.workflow.eventregistry.application.EventWaitQueryService;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventUsagesView;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventWaitView;
import io.github.illuseahashmap.workflow.shared.context.ApiRoles;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin API: runtime facts about an event key: which deployed versions use it and who waits for it. */
@RestController
@RequestMapping("/api/admin/events")
public class AdminEventController {

    private final EventWaitQueryService waits;

    public AdminEventController(EventWaitQueryService waits) {
        this.waits = waits;
    }

    @GetMapping("/{key}/usages")
    @PreAuthorize(ApiRoles.READ)
    public ApiResponse<EventUsagesView> usages(@PathVariable String key) {
        return ApiResponse.ok(waits.usages(key));
    }

    @GetMapping("/{key}/waits")
    @PreAuthorize(ApiRoles.READ)
    public ApiResponse<PageResult<EventWaitView>> waits(@PathVariable String key,
                                                        @RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(waits.waits(key, page, size));
    }
}
