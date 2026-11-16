package io.github.illuseahashmap.workflow.process.application.dto;

/** The running version of a process: the latest one, when it is not suspended. */
public record StartableProcessView(
        String processDefinitionId,
        String processDefinitionKey,
        String processDefinitionName,
        int version
) {
}
