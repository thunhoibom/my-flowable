package io.github.illuseahashmap.workflow.eventregistry.application.dto;

import java.util.List;

/** The latest deployed version of an event or channel. {@code fields} is null for a channel. */
public record DeployedModelView(String key, int version, List<EventFieldView> fields) {
}
