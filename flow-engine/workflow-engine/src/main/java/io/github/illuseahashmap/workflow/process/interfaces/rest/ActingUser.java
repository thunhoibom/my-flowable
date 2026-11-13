package io.github.illuseahashmap.workflow.process.interfaces.rest;

import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalContext;
import org.springframework.util.StringUtils;

/**
 * {@code X-Acting-User}: the person on whose behalf a service client (Flow Admin BE) calls the Deployment API.
 * It only names the audit actor. The caller is still the service client, with its own rights and tenant.
 */
public final class ActingUser {

    public static final String HEADER = "X-Acting-User";

    private static final int MAX_LENGTH = 255;

    private ActingUser() {
    }

    /** Records {@code actingUser} as the username of the current principal for this request. */
    public static void apply(String actingUser) {
        CurrentPrincipal principal = CurrentPrincipalContext.currentOrNull();
        if (principal == null || !StringUtils.hasText(actingUser)) {
            return;
        }
        String name = actingUser.strip();
        if (name.length() > MAX_LENGTH) {
            name = name.substring(0, MAX_LENGTH);
        }
        CurrentPrincipalContext.set(new CurrentPrincipal(principal.principalType(), principal.principalId(), name,
                name, principal.tenantCode(), principal.roles(), principal.permissions()));
    }
}
