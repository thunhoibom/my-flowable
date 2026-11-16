package io.github.illuseahashmap.serviceregistry.domain;

/** Where the value of one credential comes from, as in Flowable's service registry. */
public enum CredentialSource {
    /** Written in the model. Only for values that are not secret (user name, custom header value). */
    VALUE,
    /** An expression evaluated on the running process, e.g. {@code ${tenant.crmPassword}} or {@code ${token}}. */
    EXPRESSION,
    /** The name of a secret of this Flow Engine ({@code work_secret}). */
    SECRET,
    /** The registration key of an OAuth2 client of this Flow Engine; the value is its access token. */
    OAUTH2_CLIENT
}
