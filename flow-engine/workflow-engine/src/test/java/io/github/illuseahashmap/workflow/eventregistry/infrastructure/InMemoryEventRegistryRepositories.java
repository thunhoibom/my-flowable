package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import io.github.illuseahashmap.workflow.eventregistry.application.port.EventReferenceRepository;
import io.github.illuseahashmap.workflow.eventregistry.domain.EventReference;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/** In-memory event registry ports for engine-only tests. */
final class InMemoryEventRegistryRepositories {

    final References references = new References();

    /** Holds references without process versions: every stored row counts as the latest version 1. */
    static final class References implements EventReferenceRepository {

        final Map<String, List<EventReference>> rows = new TreeMap<>();

        @Override
        public void replaceForDefinition(String tenantId, String processDefinitionId,
                                         List<EventReference> references) {
            rows.put(tenantId + "|" + processDefinitionId, List.copyOf(references));
        }

        @Override
        public void deleteForDefinition(String tenantId, String processDefinitionId) {
            rows.remove(tenantId + "|" + processDefinitionId);
        }

        @Override
        public void deleteForProcessKey(String tenantId, String processDefinitionKey) {
            rows.keySet().removeIf(id -> id.startsWith(tenantId + "|" + processDefinitionKey + ":"));
        }

        @Override
        public long latestProcessDefinitionsUsing(String tenantId, String eventKey) {
            return using(tenantId, eventKey).map(EventReference::processDefinitionId).distinct().count();
        }

        @Override
        public List<EventUsage> usages(String tenantId, String eventKey) {
            return using(tenantId, eventKey)
                    .map(reference -> new EventUsage(reference.processDefinitionId().split(":")[0], 1,
                            reference.activityId()))
                    .sorted(Comparator.comparing(EventUsage::processDefinitionKey)
                            .thenComparing(EventUsage::activityId))
                    .toList();
        }

        private Stream<EventReference> using(String tenantId, String eventKey) {
            return rows.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(tenantId + "|"))
                    .flatMap(entry -> entry.getValue().stream())
                    .filter(reference -> reference.eventKey().equals(eventKey));
        }
    }

}
