package io.github.illuseahashmap.workflow.serviceregistry;

import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/** Name rules and the acting user, shared by the secret, OAuth2 client and tenant variable endpoints. */
final class ServiceConfigNames {

    /** Secret names and OAuth2 registration keys, same as service keys. */
    private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,127}");
    /** Tenant variable names are read as {@code ${tenant.<name>}}, so they must be expression identifiers. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    private ServiceConfigNames() {
    }

    static String requireKey(String value, String field) {
        if (value == null || !KEY.matcher(value).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " không hợp lệ");
        }
        return value;
    }

    static String requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    field + " chỉ gồm chữ, số và dấu _ (không bắt đầu bằng số)");
        }
        return value;
    }

    static String actor(CurrentPrincipalProvider principals) {
        CurrentPrincipal principal = principals.current();
        return StringUtils.hasText(principal.username()) ? principal.username() : principal.principalId();
    }
}
