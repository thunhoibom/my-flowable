package io.github.illuseahashmap.workflow.process.application;

import io.github.illuseahashmap.workflow.process.application.dto.ProcessDeploymentCommand;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDeploymentRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDeploymentResult;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessVersionContent;
import java.util.List;
import java.util.Optional;

/**
 * The receiving side of a process deployment (architecture spec, "Deployment API"). The engine is the last to
 * check: every guard runs again here, whatever Design checked before.
 */
public interface ProcessDeploymentService {

    /** {@code POST /api/deployment/processes}: reads the request services and deploys. */
    ProcessDeploymentResult deploy(ProcessDeploymentRequest request);

    /**
     * Guards, then BPMN and services in one deployment, assignment rules and references, in one transaction.
     * The same publish id deploys once: sending it again returns the version it created.
     */
    ProcessDeploymentResult deploy(ProcessDeploymentCommand command);

    /** The latest version of every key of the tenant. */
    List<ProcessDeploymentResult> list();

    /** The latest version of the key, empty when it was never deployed. */
    Optional<ProcessDeploymentResult> latest(String processKey);

    /** BPMN and services of one version; the latest one when {@code version} is null. */
    ProcessVersionContent version(String processKey, Integer version);

    /** Suspends the latest version; running instances go on. */
    ProcessDeploymentResult suspend(String processKey);

    ProcessDeploymentResult resume(String processKey);

    /** Deletes a version that is not the latest and has no running instance. */
    void deleteVersion(String processKey, int version);

    /** Deletes every version of the key. Refused while an instance runs or another process calls it. */
    void deleteProcess(String processKey);
}
