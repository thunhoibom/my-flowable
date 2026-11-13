package io.github.illuseahashmap.workflow.process.application.port;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/** What a process deployment needs from the service registry, without depending on it. */
public interface ServiceDeploymentSupport {

    /**
     * The {@code <serviceKey>.service} content for a service definition sent with a deployment: the design only,
     * with base URLs and credentials removed. Throws {@link IllegalArgumentException} when the definition cannot be
     * read or names another service.
     */
    byte[] designOnly(String serviceKey, Map<String, Object> definition);

    /** The keys among {@code serviceKeys} that have no environment settings (base URL, credentials) here. */
    Set<String> withoutEnvironment(String tenantId, Collection<String> serviceKeys);
}
