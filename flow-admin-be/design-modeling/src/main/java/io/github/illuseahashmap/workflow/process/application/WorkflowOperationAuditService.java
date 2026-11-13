package io.github.illuseahashmap.workflow.process.application;

import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Audit of Design actions in Flow Admin BE. The operation audit table belongs to Flow Engine, which records every
 * deployment it receives with the acting user. Here the Design side only writes a structured log line.
 */
@Service
public class WorkflowOperationAuditService {

    private static final Logger AUDIT = LoggerFactory.getLogger("flow-admin-be.audit");

    private final CurrentPrincipalProvider principalProvider;
    private final TenantProvider tenantProvider;

    public WorkflowOperationAuditService(CurrentPrincipalProvider principalProvider, TenantProvider tenantProvider) {
        this.principalProvider = principalProvider;
        this.tenantProvider = tenantProvider;
    }

    public void record(String eventType, String processInstanceId, String processDefinitionKey, String taskId,
                       String subject, String previousState, String nextState, String reason) {
        CurrentPrincipal actor = principalProvider.current();
        AUDIT.info("event={} tenant={} actor={} processKey={} subject={} from={} to={} reason={}", eventType,
                tenantProvider.current().tenantCode(), actor.username(), processDefinitionKey, subject,
                previousState, nextState, reason);
    }
}
