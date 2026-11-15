package io.github.illuseahashmap.workflow.process.application.dto;

import java.time.OffsetDateTime;

/** A Flowable dead-letter job: it failed every retry and waits for an admin. */
public record FailedJobView(
        String jobId,
        String jobType,
        String processInstanceId,
        String processDefinitionId,
        String processDefinitionKey,
        String executionId,
        String activityId,
        String activityName,
        String exceptionMessage,
        OffsetDateTime createTime,
        OffsetDateTime dueDate
) {
}
