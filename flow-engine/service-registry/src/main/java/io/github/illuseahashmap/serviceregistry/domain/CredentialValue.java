package io.github.illuseahashmap.serviceregistry.domain;

/** One credential in a service model: a source and what it names (a value, an expression, a secret, a client). */
public record CredentialValue(CredentialSource source, String value) {

    public boolean missing() {
        return source == null || value == null || value.isBlank();
    }
}
