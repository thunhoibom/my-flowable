package io.github.illuseahashmap.serviceregistry.domain;

import java.util.List;

/**
 * A service as deployed with a process: its base URL (may contain an expression such as
 * {@code ${tenant.crmBaseUrl}}), its authorization (references only, never a secret) and its operations.
 * Runtime reads this snapshot, so later draft edits do not change a running process.
 */
public record PublishedService(
        String serviceKey,
        String name,
        String description,
        String baseUrl,
        Authorization authorization,
        List<PublishedOperation> operations
) {

    public PublishedService {
        operations = operations == null ? List.of() : List.copyOf(operations);
        authorization = Authorization.orNone(authorization);
    }
}
