package io.github.illuseahashmap.serviceregistry.application;

import io.github.illuseahashmap.serviceregistry.domain.InvalidServiceRequestException;

/**
 * Evaluates an expression of a service model (a base URL, a credential) on the running process, with the same
 * expression manager as the task's input mapping; {@code ${tenant.<name>}} reads a tenant variable.
 */
@FunctionalInterface
public interface ExpressionEvaluator {

    /** For calls made outside a process: any expression is refused. */
    ExpressionEvaluator NONE = expression -> {
        throw new InvalidServiceRequestException("Expression needs a running process: " + expression);
    };

    /** @return the value as text, null when the expression yields null */
    String evaluate(String expression);

    /** Evaluates {@code text} when it contains an expression, otherwise returns it unchanged. */
    default String text(String text) {
        if (text == null || !(text.contains("${") || text.contains("#{"))) {
            return text;
        }
        return evaluate(text);
    }
}
