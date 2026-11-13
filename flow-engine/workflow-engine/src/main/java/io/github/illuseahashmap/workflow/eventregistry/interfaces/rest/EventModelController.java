package io.github.illuseahashmap.workflow.eventregistry.interfaces.rest;

import io.github.illuseahashmap.workflow.eventregistry.application.EventWaitQueryService;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventUsagesView;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runtime facts about an event model: which deployed versions use it and how many executions wait on it. The model
 * library itself (drafts, publishing) is in Flow Admin BE.
 */
@RestController
@RequestMapping("/workflow/management/event-registry/events")
public class EventModelController {

    private static final String READ =
            "hasAnyRole('SERVICE', 'WF_PLATFORM_ADMIN', 'WF_ADMIN', 'WF_OPERATOR', 'WF_VIEWER')";

    private final EventWaitQueryService waits;

    public EventModelController(EventWaitQueryService waits) {
        this.waits = waits;
    }

    @GetMapping("/{key}/usages")
    @PreAuthorize(READ)
    public ApiResponse<EventUsagesView> usages(@PathVariable String key) {
        return ApiResponse.ok(waits.usages(key));
    }
}
