package io.github.illuseahashmap.workflow.eventregistry.application;

import io.github.illuseahashmap.workflow.eventregistry.application.dto.DeployedModelView;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventDeploymentRequest;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.EventDeploymentResult;
import java.util.Optional;

/**
 * Receiving side of an event deployment (architecture spec, "Deploy event"). Like Flowable, the latest version of
 * a key wins: every process waiting on the key follows it. Content equal to the running version is not deployed
 * again, so versions only grow when something changed. Events and channels go in one transaction.
 */
public interface EventDeploymentService {

    EventDeploymentResult deploy(EventDeploymentRequest request);

    /** The latest deployed version of an event and its fields, empty when none is deployed. */
    Optional<DeployedModelView> event(String key);

    Optional<DeployedModelView> channel(String key);

    /** Refused while a process waits on the event or the latest version of a process uses it. */
    void deleteEvent(String key);

    void deleteChannel(String key);
}
