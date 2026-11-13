package io.github.illuseahashmap.workflow.process.application.dto;

import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentRuleInheritResult;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A deployed process version. {@code suspended} tells whether new instances can start.
 *
 * @param assignments    the copy of assignment rules this deployment asked for, null when none was asked
 * @param newUserTaskIds user tasks that the previous latest version did not have, so they have no rule yet
 */
public record ProcessDeploymentResult(
        String deploymentId,
        String processDefinitionId,
        String processKey,
        String name,
        int version,
        boolean suspended,
        OffsetDateTime deployTime,
        AssignmentRuleInheritResult assignments,
        List<String> newUserTaskIds
) {
    public ProcessDeploymentResult {
        newUserTaskIds = newUserTaskIds == null ? List.of() : List.copyOf(newUserTaskIds);
    }
}
