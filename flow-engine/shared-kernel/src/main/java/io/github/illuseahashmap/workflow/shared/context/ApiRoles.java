package io.github.illuseahashmap.workflow.shared.context;

/**
 * Roles of the user-facing APIs: Admin API ({@code /api/admin/**}, Hub and Design) and the Work reads of the
 * Runtime API. Flow Admin calls them with the user's own token, so these are EIM roles; the service role is not
 * accepted.
 */
public final class ApiRoles {

    /** Read runtime data. */
    public static final String READ = "hasAnyRole('WF_PLATFORM_ADMIN', 'WF_ADMIN', 'WF_OPERATOR', 'WF_VIEWER')";
    /** Act on running instances, tasks and jobs. */
    public static final String OPERATE = "hasAnyRole('WF_PLATFORM_ADMIN', 'WF_ADMIN', 'WF_OPERATOR')";
    /** Change what is deployed and how it is configured. */
    public static final String MANAGE = "hasAnyRole('WF_PLATFORM_ADMIN', 'WF_ADMIN')";

    private ApiRoles() {
    }
}
