package io.github.illuseahashmap.workflow.assignment.interfaces.rest;

import io.github.illuseahashmap.workflow.assignment.application.AssignmentRuleService;
import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentRuleCommand;
import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentRuleInheritCommand;
import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentRuleInheritResult;
import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentRuleView;
import io.github.illuseahashmap.workflow.shared.context.ApiRoles;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API: assignment rules of deployed versions. The spec wants rules fixed with the version, but the user kept
 * editing them here because Design has no draft rules (phase 1 and 2 decisions).
 */
@RestController
@RequestMapping("/api/admin/assignment-rules")
@PreAuthorize(ApiRoles.MANAGE)
public class AdminAssignmentRuleController {

    private final AssignmentRuleService rules;

    public AdminAssignmentRuleController(AssignmentRuleService rules) {
        this.rules = rules;
    }

    @GetMapping
    public ApiResponse<PageResult<AssignmentRuleView>> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String processDefinitionKey,
            @RequestParam(required = false) String processDefinitionId,
            @RequestParam(required = false) Integer version,
            @RequestParam(required = false) String taskDefinitionKey,
            @RequestParam(required = false) String variableName,
            @RequestParam(required = false) String assignmentType,
            @RequestParam(required = false) String emptyUserStrategy) {
        return ApiResponse.ok(rules.page(pageNum, pageSize, processDefinitionKey, processDefinitionId, version,
                taskDefinitionKey, variableName, assignmentType, emptyUserStrategy));
    }

    @PostMapping
    public ApiResponse<AssignmentRuleView> create(@Valid @RequestBody AssignmentRuleCommand command) {
        return ApiResponse.ok(rules.create(command));
    }

    /** Copies the rules of the previous version into this one. */
    @PostMapping("/inherit")
    public ApiResponse<AssignmentRuleInheritResult> inherit(@Valid @RequestBody AssignmentRuleInheritCommand command) {
        return ApiResponse.ok(rules.inherit(command.processDefinitionId()));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable long id, @Valid @RequestBody AssignmentRuleCommand command) {
        rules.update(id, command);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        rules.delete(id);
        return ApiResponse.ok();
    }
}
