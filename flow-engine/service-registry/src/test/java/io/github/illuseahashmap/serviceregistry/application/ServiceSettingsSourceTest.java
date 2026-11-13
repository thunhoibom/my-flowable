package io.github.illuseahashmap.serviceregistry.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.illuseahashmap.serviceregistry.domain.ServiceSettings;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ServiceSettingsSourceTest {

    @Test
    void firstOfAsksEachSourceInTurn() {
        ServiceSettings first = new ServiceSettings("https://first.example", null, Map.of());
        ServiceSettings second = new ServiceSettings("https://second.example", null, Map.of());
        ServiceSettingsSource source = ServiceSettingsSource.firstOf(
                (tenantId, key) -> "a".equals(key) ? Optional.of(first) : Optional.empty(),
                (tenantId, key) -> "z".equals(key) ? Optional.empty() : Optional.of(second));

        assertEquals("https://first.example", source.find("t", "a").orElseThrow().baseUrl());
        assertEquals("https://second.example", source.find("t", "b").orElseThrow().baseUrl());
        assertTrue(source.find("t", "z").isEmpty());
    }
}
