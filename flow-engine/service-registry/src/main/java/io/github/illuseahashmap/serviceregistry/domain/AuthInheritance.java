package io.github.illuseahashmap.serviceregistry.domain;

/** Picks the authorization of one operation, as Flowable does: the model's, or the operation's own override. */
public final class AuthInheritance {

    private AuthInheritance() {
    }

    public static Authorization resolve(AuthMode mode, Authorization service, Authorization operation) {
        AuthMode effective = mode == null ? AuthMode.INHERIT : mode;
        return switch (effective) {
            case INHERIT -> Authorization.orNone(service);
            case OVERRIDE -> Authorization.orNone(operation);
            // NONE suppresses the service authorization for a public operation on a protected service.
            case NONE -> Authorization.NONE;
        };
    }
}
