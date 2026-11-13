package io.github.illuseahashmap.workflow.process.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /api/deployment/processes}: everything a process version needs to run, sent by Flow Admin BE.
 *
 * @param publishId   id of this publish, chosen by the sender; sending it again returns the first result
 * @param tenantId    optional; when present it must be the tenant of the service token
 * @param services    design of every service the BPMN calls (operations, parameters, error rules), one JSON
 *                    object each with its {@code serviceKey}; no base URL and no secret
 * @param assignments assignment rules of the user tasks, fixed with this version
 * @param copyAssignmentsFromLatest  copy the rules of the latest version before this one (Design without draft rules)
 * @param copyAssignmentsFromVersion copy the rules of this version (a rollback to it)
 */
public record ProcessDeploymentRequest(
        @NotBlank @Size(max = 255) String publishId,
        String tenantId,
        @NotBlank @Size(max = 128) String processKey,
        @Size(max = 255) String name,
        @NotBlank String bpmnXml,
        List<Map<String, Object>> services,
        List<@Valid DeploymentAssignmentRule> assignments,
        Boolean copyAssignmentsFromLatest,
        Integer copyAssignmentsFromVersion
) {
}
