package io.github.illuseahashmap.serviceregistry.application;

import java.util.Map;
import java.util.Optional;

/**
 * Overrides from this Flow Engine's configuration, as Flowable's
 * {@code flowable.service-registry.rest-settings.<serviceDefinitionKey | default>.*}: the service's own entry wins,
 * then {@code default}, then the model. Here: {@code workflow.service-registry.rest-settings.<key | default>.base-url}
 * and {@code .timeout} (read timeout, milliseconds).
 */
public record RestSettings(Map<String, Setting> settings) {

    public static final String DEFAULT = "default";
    public static final RestSettings NONE = new RestSettings(Map.of());

    public RestSettings {
        settings = settings == null ? Map.of() : Map.copyOf(settings);
    }

    public record Setting(String baseUrl, Integer timeout) {
    }

    public Optional<String> baseUrl(String serviceKey) {
        return first(serviceKey, setting -> blankToNull(setting.baseUrl()));
    }

    public Optional<Integer> timeout(String serviceKey) {
        return first(serviceKey, setting -> setting.timeout() == null || setting.timeout() <= 0 ? null
                : setting.timeout());
    }

    private <T> Optional<T> first(String serviceKey, java.util.function.Function<Setting, T> field) {
        for (String key : new String[] {serviceKey, DEFAULT}) {
            Setting setting = settings.get(key);
            T value = setting == null ? null : field.apply(setting);
            if (value != null) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
