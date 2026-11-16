package io.github.illuseahashmap.workflow.serviceregistry;

import io.github.illuseahashmap.serviceregistry.infrastructure.JdbcTenantVariableStore;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.variable.VariableContainer;
import org.flowable.common.engine.impl.javax.el.ELContext;
import org.flowable.common.engine.impl.javax.el.ELResolver;

/**
 * {@code ${tenant.<name>}} in any Flowable expression: the tenant variable of the running process's tenant, as in
 * Flowable ("referenced in expressions with the tenant prefix"). A protected variable is decrypted here and only
 * lives in memory. An unknown name fails the expression instead of yielding null. Registered before the default
 * resolvers, so it hides a process variable named {@code tenant}.
 */
public final class TenantVariableELResolver extends ELResolver {

    public static final String NAME = "tenant";

    private final JdbcTenantVariableStore store;

    public TenantVariableELResolver(JdbcTenantVariableStore store) {
        this.store = store;
    }

    /** The {@code tenant} root of an expression, bound to one tenant. */
    public record TenantVariables(String tenantId) {
    }

    @Override
    public Object getValue(ELContext context, Object base, Object property) {
        if (base == null && NAME.equals(property)) {
            context.setPropertyResolved(true);
            return new TenantVariables(tenantId(context));
        }
        if (base instanceof TenantVariables variables && property != null) {
            context.setPropertyResolved(true);
            String name = property.toString();
            return store.value(variables.tenantId(), name)
                    .orElseThrow(() -> new FlowableException("Unknown tenant variable: " + name));
        }
        return null;
    }

    @Override
    public Class<?> getType(ELContext context, Object base, Object property) {
        if ((base == null && NAME.equals(property)) || base instanceof TenantVariables) {
            context.setPropertyResolved(true);
            return Object.class;
        }
        return null;
    }

    @Override
    public void setValue(ELContext context, Object base, Object property, Object value) {
        if ((base == null && NAME.equals(property)) || base instanceof TenantVariables) {
            throw new FlowableException("Tenant variables are read-only in expressions");
        }
    }

    @Override
    public boolean isReadOnly(ELContext context, Object base, Object property) {
        if ((base == null && NAME.equals(property)) || base instanceof TenantVariables) {
            context.setPropertyResolved(true);
            return true;
        }
        return false;
    }

    @Override
    public Class<?> getCommonPropertyType(ELContext context, Object base) {
        return base instanceof TenantVariables ? String.class : null;
    }

    private static String tenantId(ELContext context) {
        Object container = context.getContext(VariableContainer.class);
        String tenantId = container instanceof VariableContainer variables ? variables.getTenantId() : null;
        // Flowable keeps the default tenant as an empty tenant id.
        return tenantId == null ? "" : tenantId;
    }
}
