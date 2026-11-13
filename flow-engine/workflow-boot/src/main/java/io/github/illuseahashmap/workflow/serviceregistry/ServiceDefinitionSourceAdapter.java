package io.github.illuseahashmap.workflow.serviceregistry;

import io.github.illuseahashmap.serviceregistry.domain.ServiceCatalog;
import io.github.illuseahashmap.serviceregistry.engine.ServiceModelJson;
import io.github.illuseahashmap.workflow.process.application.port.ServiceDefinitionSource;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * The latest published version of each service, as the {@code .service} resource a process deployment carries.
 * Base URLs and credentials are removed here; they are read from the catalog at call time.
 */
@Component
public class ServiceDefinitionSourceAdapter implements ServiceDefinitionSource {

    private final ServiceCatalog catalog;
    private final ServiceModelJson json = new ServiceModelJson();

    public ServiceDefinitionSourceAdapter(ServiceCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public Map<String, byte[]> latestPublished(Collection<String> serviceKeys) {
        Map<String, byte[]> resources = new TreeMap<>();
        for (String serviceKey : serviceKeys) {
            catalog.publishedService(serviceKey).ifPresent(service -> resources.put(serviceKey, json.write(service)));
        }
        return resources;
    }
}
