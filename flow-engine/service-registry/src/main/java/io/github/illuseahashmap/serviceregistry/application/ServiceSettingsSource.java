package io.github.illuseahashmap.serviceregistry.application;

import io.github.illuseahashmap.serviceregistry.domain.ServiceSettings;
import java.util.Optional;

/** Current environment settings of a service. Read on every call, so a change takes effect immediately. */
@FunctionalInterface
public interface ServiceSettingsSource {

    Optional<ServiceSettings> find(String tenantId, String serviceKey);

    /** Asks each source in turn and returns the first settings found. */
    static ServiceSettingsSource firstOf(ServiceSettingsSource... sources) {
        return (tenantId, serviceKey) -> {
            for (ServiceSettingsSource source : sources) {
                Optional<ServiceSettings> found = source.find(tenantId, serviceKey);
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        };
    }
}
