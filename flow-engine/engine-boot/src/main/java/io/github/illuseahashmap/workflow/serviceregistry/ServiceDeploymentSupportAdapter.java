package io.github.illuseahashmap.workflow.serviceregistry;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.serviceregistry.application.ServiceEnvironment;
import io.github.illuseahashmap.serviceregistry.domain.Authorization;
import io.github.illuseahashmap.serviceregistry.domain.PublishedService;
import io.github.illuseahashmap.serviceregistry.engine.ServiceModelJson;
import io.github.illuseahashmap.workflow.process.application.port.ServiceDeploymentSupport;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * Reads the service models sent with a deployment, and tells which secrets, OAuth2 clients and tenant variables
 * they reference that this Flow Engine does not have.
 */
@Component
public class ServiceDeploymentSupportAdapter implements ServiceDeploymentSupport {

    private final ServiceEnvironment environment;
    private final ServiceModelJson json = new ServiceModelJson();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public ServiceDeploymentSupportAdapter(ServiceEnvironment environment) {
        this.environment = environment;
    }

    @Override
    public byte[] serviceResource(String serviceKey, Map<String, Object> definition) {
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
        List<String> problems = ServiceModelJson.problems(service);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        return json.write(service);
    }

    @Override
    public Set<String> missingConfiguration(String tenantId, Map<String, byte[]> services) {
        Set<String> secrets = new TreeSet<>();
        Set<String> clients = new TreeSet<>();
        Set<String> variables = new TreeSet<>();
        for (byte[] content : services.values()) {
            PublishedService service = json.read(content);
            for (Authorization authorization : ServiceModelJson.authorizations(service)) {
                secrets.addAll(authorization.secrets());
                clients.addAll(authorization.oauth2Clients());
            }
            variables.addAll(ServiceModelJson.tenantVariables(service));
        }
        Set<String> missing = new LinkedHashSet<>();
        environment.missingSecrets(tenantId, secrets).forEach(name -> missing.add("secret " + name));
        environment.missingOauth2Clients(tenantId, clients).forEach(key -> missing.add("OAuth2 client " + key));
        environment.missingTenantVariables(tenantId, variables).forEach(name -> missing.add("biến tenant " + name));
        return missing;
    }
}
