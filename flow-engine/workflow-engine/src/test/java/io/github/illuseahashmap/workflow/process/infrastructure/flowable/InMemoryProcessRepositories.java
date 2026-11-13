package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.process.application.port.CallReferenceRepository;
import io.github.illuseahashmap.workflow.process.domain.CallReference;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Port-contract fakes for engine tests that do not need PostgreSQL. */
public final class InMemoryProcessRepositories {

    private InMemoryProcessRepositories() {
    }

    public static final class References implements CallReferenceRepository {

        private final Map<String, List<CallReference>> rows = new LinkedHashMap<>();

        @Override
        public void replaceForDefinition(String tenantId, String parentDefinitionId, List<CallReference> references) {
            rows.put(tenantId + "/" + parentDefinitionId, List.copyOf(references));
        }

        @Override
        public void deleteForDefinition(String tenantId, String parentDefinitionId) {
            rows.remove(tenantId + "/" + parentDefinitionId);
        }

        @Override
        public void deleteForKey(String tenantId, String parentDefinitionKey) {
            rows.entrySet().removeIf(entry -> entry.getKey().startsWith(tenantId + "/")
                    && entry.getValue().stream().anyMatch(ref -> ref.parentDefinitionKey().equals(parentDefinitionKey)));
        }

        @Override
        public List<CallReference> findCallers(String tenantId, String calledKey) {
            return findAll(tenantId).stream().filter(ref -> ref.calledKey().equals(calledKey)).toList();
        }

        @Override
        public List<CallReference> findByDefinition(String tenantId, String parentDefinitionId) {
            return rows.getOrDefault(tenantId + "/" + parentDefinitionId, List.of());
        }

        @Override
        public List<CallReference> findAll(String tenantId) {
            return rows.entrySet().stream().filter(entry -> entry.getKey().startsWith(tenantId + "/"))
                    .flatMap(entry -> entry.getValue().stream()).toList();
        }
    }

}
