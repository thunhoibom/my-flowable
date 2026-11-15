package io.github.illuseahashmap.workflow.process.application.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * Admin API: hand a running task to someone else, whoever holds it now. Exactly one of {@code targetAssignee} and
 * {@code targetCandidateUsers} is set.
 */
public record ReassignTaskRequest(
        String targetAssignee,
        List<String> targetCandidateUsers,
        @NotBlank String reason
) {
}
