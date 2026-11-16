package io.github.illuseahashmap.workflow.process.application.dto;

import java.time.OffsetDateTime;

/** A running task the current user can act on: assigned to him, or he is a candidate user. */
public record InboxTaskView(
        String taskId,
        String taskName,
        String taskDefinitionKey,
        String processInstanceId,
        String processDefinitionId,
        String processDefinitionKey,
        String processDefinitionName,
        String businessKey,
        String assignee,
        OffsetDateTime createTime,
        OffsetDateTime dueDate
) {
}
