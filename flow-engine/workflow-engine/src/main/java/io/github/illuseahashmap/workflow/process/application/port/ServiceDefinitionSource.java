package io.github.illuseahashmap.workflow.process.application.port;

import java.util.Collection;
import java.util.Map;

/**
 * Where a process deployment takes its service definitions from. Each value is the content of a
 * {@code <serviceKey>.service} resource that goes into the same Flowable deployment as the BPMN.
 */
public interface ServiceDefinitionSource {

    /** Resource suffix, same as Flowable service definitions. */
    String RESOURCE_SUFFIX = ".service";

    static String resourceName(String serviceKey) {
        return serviceKey + RESOURCE_SUFFIX;
    }

    /**
     * The latest published version of each key, keyed by service key.
     * A key that was never published is left out, so the caller can report every missing key at once.
     */
    Map<String, byte[]> latestPublished(Collection<String> serviceKeys);
}
