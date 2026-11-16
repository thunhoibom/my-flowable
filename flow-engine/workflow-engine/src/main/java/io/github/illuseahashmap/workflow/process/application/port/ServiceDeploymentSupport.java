package io.github.illuseahashmap.workflow.process.application.port;

import java.util.Map;
import java.util.Set;

/** What a process deployment needs from the service registry, without depending on it. */
public interface ServiceDeploymentSupport {

    /**
     * The {@code <serviceKey>.service} content for a service model sent with a deployment: base URL, authorization
     * (references only) and operations. Throws {@link IllegalArgumentException} when the model cannot be read,
     * names another service, or holds a secret value.
     */
    byte[] serviceResource(String serviceKey, Map<String, Object> definition);

    /**
     * What the given {@code .service} resources reference and this Flow Engine does not have, as labels such as
     * {@code secret crmToken}, {@code OAuth2 client crm-oauth}, {@code biến tenant crmBaseUrl}. Empty when complete.
     */
    Set<String> missingConfiguration(String tenantId, Map<String, byte[]> services);
}
