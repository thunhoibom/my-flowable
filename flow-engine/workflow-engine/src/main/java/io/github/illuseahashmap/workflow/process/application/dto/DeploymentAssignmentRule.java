package io.github.illuseahashmap.workflow.process.application.dto;

import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentConditionCommand;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentType;
import io.github.illuseahashmap.workflow.assignment.domain.EmptyUserStrategy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * One assignment rule sent with a process deployment, for the user task {@code nodeId}. It is bound to the
 * deployed definition and fixed with it: a new rule set means a new version.
 */
public record DeploymentAssignmentRule(
        @NotBlank String nodeId,
        Integer priority,
        @NotNull AssignmentType assignmentType,
        List<String> assignees,
        List<String> candidateUsers,
        List<String> candidateGroups,
        List<String> countersignUsers,
        EmptyUserStrategy emptyUserStrategy,
        String fallbackAssignee,
        Boolean enabled,
        String description,
        List<@Valid AssignmentConditionCommand> conditions
) {
}
