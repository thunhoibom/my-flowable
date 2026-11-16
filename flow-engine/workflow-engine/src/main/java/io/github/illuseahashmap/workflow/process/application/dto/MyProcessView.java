package io.github.illuseahashmap.workflow.process.application.dto;

import java.time.OffsetDateTime;

/** A process instance the current user started. {@code status}: RUNNING, FINISHED or TERMINATED. */
public record MyProcessView(
        String processInstanceId,
        String processDefinitionId,
        String processDefinitionKey,
        String processDefinitionName,
        Integer processDefinitionVersion,
        String businessKey,
        String status,
        OffsetDateTime startTime,
        OffsetDateTime endTime
) {
}
