package io.github.illuseahashmap.workflow.process.application.dto;

import java.util.Map;

/**
 * What one deployed version is made of: its BPMN and the {@code .service} definitions deployed with it, by service
 * key. Design reads it to roll back, or to open an old version in the draft.
 */
public record ProcessVersionContent(
        String processKey,
        String name,
        int version,
        String processDefinitionId,
        String bpmnXml,
        Map<String, String> services
) {
}
