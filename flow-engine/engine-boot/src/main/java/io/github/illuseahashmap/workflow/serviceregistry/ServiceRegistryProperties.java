package io.github.illuseahashmap.workflow.serviceregistry;

import io.github.illuseahashmap.serviceregistry.application.RestSettings;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code workflow.service-registry.rest-settings.<serviceKey | default>.base-url | timeout}, like Flowable's
 * {@code flowable.service-registry.rest-settings.*}: per-environment overrides in this Flow Engine's configuration.
 */
public class ServiceRegistryProperties {

    private Map<String, RestSetting> restSettings = new LinkedHashMap<>();

    public Map<String, RestSetting> getRestSettings() {
        return restSettings;
    }

    public void setRestSettings(Map<String, RestSetting> restSettings) {
        this.restSettings = restSettings == null ? new LinkedHashMap<>() : restSettings;
    }

    RestSettings restSettings() {
        Map<String, RestSettings.Setting> settings = new LinkedHashMap<>();
        restSettings.forEach((key, setting) -> settings.put(key,
                new RestSettings.Setting(setting.getBaseUrl(), setting.getTimeout())));
        return new RestSettings(settings);
    }

    /** One {@code rest-settings} entry. */
    public static class RestSetting {

        private String baseUrl;
        private Integer timeout;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Integer getTimeout() {
            return timeout;
        }

        public void setTimeout(Integer timeout) {
            this.timeout = timeout;
        }
    }
}
