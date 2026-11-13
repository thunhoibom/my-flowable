package io.github.illuseahashmap.workflow.eventregistry.application.dto;

import java.util.List;

/** The version of each event and channel after the deployment, and whether this request created it. */
public record EventDeploymentResult(List<Item> events, List<Item> channels) {

    /** {@code deployed} is false when the content equals the running version, which then stays as it is. */
    public record Item(String key, int version, boolean deployed) {
    }
}
