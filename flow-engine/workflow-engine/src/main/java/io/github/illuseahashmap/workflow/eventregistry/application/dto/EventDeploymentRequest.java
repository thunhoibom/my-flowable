package io.github.illuseahashmap.workflow.eventregistry.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code POST /api/deployment/events}: shared events and channels, published apart from any process so that
 * publishing a process can never change an event another process uses.
 *
 * @param publishId id of this publish, for the audit. Sending the same content again deploys nothing new, so the
 *                  request is safe to resend.
 * @param tenantId  optional; when present it must be the tenant of the service token
 */
public record EventDeploymentRequest(
        @NotBlank @Size(max = 255) String publishId,
        String tenantId,
        List<@Valid Event> events,
        List<@Valid Channel> channels
) {

    /** An event model. {@code fields} use the Flowable type names. */
    public record Event(
            @NotBlank @Size(max = 255) String key,
            @NotBlank @Size(max = 256) String name,
            @Size(max = 1024) String description,
            @NotNull List<EventFieldView> fields) {
    }

    /**
     * An inbound REST channel. {@code detection} is FIXED, JSON_FIELD or JSON_POINTER; with FIXED the allowed events
     * are just {@code detectionValue}. Every allowed event must be deployed, or be in the same request.
     */
    public record Channel(
            @NotBlank @Size(max = 255) String key,
            @NotBlank @Size(max = 256) String name,
            @Size(max = 1024) String description,
            @NotBlank String detection,
            @NotBlank @Size(max = 256) String detectionValue,
            List<String> allowedEventKeys,
            List<String> userIds,
            List<String> groupIds) {
    }
}
