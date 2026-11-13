package io.github.illuseahashmap.workflow.serviceregistry;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.serviceregistry.application.ServiceSettingsSource;
import io.github.illuseahashmap.serviceregistry.domain.PublishedService;
import io.github.illuseahashmap.serviceregistry.engine.ServiceModelJson;
import io.github.illuseahashmap.workflow.process.application.port.ServiceDeploymentSupport;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/** Reads service definitions sent with a deployment, and tells which services have no environment settings. */
@Component
public class ServiceDeploymentSupportAdapter implements ServiceDeploymentSupport {

    private final ServiceSettingsSource settings;
    private final ServiceModelJson json = new ServiceModelJson();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public ServiceDeploymentSupportAdapter(ServiceSettingsSource settings) {
        this.settings = settings;
    }

    @Override
    public byte[] designOnly(String serviceKey, Map<String, Object> definition) {
        PublishedService service;
        try {
            service = objectMapper.convertValue(definition, PublishedService.class);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("không đọc được định nghĩa", exception);
        }
        if (!serviceKey.equals(service.serviceKey())) {
            throw new IllegalArgumentException("serviceKey không khớp");
        }
        if (service.operations().isEmpty()) {
            throw new IllegalArgumentException("không có operation nào");
        }
        // Whatever environment data the sender left in, the deployment keeps only the design.
        return json.write(service);
    }

    @Override
    public Set<String> withoutEnvironment(String tenantId, Collection<String> serviceKeys) {
        Set<String> missing = new TreeSet<>();
        for (String serviceKey : serviceKeys) {
            if (settings.find(tenantId, serviceKey).isEmpty()) {
                missing.add(serviceKey);
            }
        }
        return missing;
    }
}
