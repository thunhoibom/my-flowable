package io.github.illuseahashmap.serviceregistry.engine;

import io.github.illuseahashmap.serviceregistry.domain.PublishedService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Parsed service models by definition id, like Flowable's DefaultDeploymentCache: LRU when a limit is set.
 * A deployed definition never changes, so entries are only removed when their deployment is deleted.
 */
final class ServiceDefinitionCache {

    private final Map<String, PublishedService> entries;

    ServiceDefinitionCache(int limit) {
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, PublishedService> eldest) {
                return limit > 0 && size() > limit;
            }
        };
    }

    /** The loader runs outside the lock, so a slow read does not block other definitions. */
    PublishedService get(String definitionId, Supplier<PublishedService> loader) {
        synchronized (this) {
            PublishedService cached = entries.get(definitionId);
            if (cached != null) {
                return cached;
            }
        }
        PublishedService loaded = loader.get();
        synchronized (this) {
            entries.put(definitionId, loaded);
        }
        return loaded;
    }

    synchronized void remove(String definitionId) {
        entries.remove(definitionId);
    }
}
