package io.github.illuseahashmap.workflow.process.application.dto;

import java.util.List;
import java.util.Map;

/**
 * A process deployment as the engine runs it. {@code services} holds the {@code .service} content of each called
 * service, by service key, already reduced to the design.
 */
public record ProcessDeploymentCommand(
        String publishId,
        String processKey,
        String name,
        String bpmnXml,
        Map<String, byte[]> services,
        List<DeploymentAssignmentRule> assignments,
        boolean copyAssignmentsFromLatest,
        Integer copyAssignmentsFromVersion
) {
    public ProcessDeploymentCommand {
        services = services == null ? Map.of() : Map.copyOf(services);
        assignments = assignments == null ? List.of() : List.copyOf(assignments);
    }
}
