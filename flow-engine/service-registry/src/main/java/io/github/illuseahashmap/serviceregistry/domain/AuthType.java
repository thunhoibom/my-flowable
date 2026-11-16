package io.github.illuseahashmap.serviceregistry.domain;

/**
 * Authentication of a service call. BASIC, BEARER, API_KEY and CUSTOM_HEADER follow Flowable's service registry;
 * MTLS, HMAC and AWS_SIG_V4 are extensions. An OAuth2 token is a BEARER or CUSTOM_HEADER value taken from an
 * OAuth2 client; a token held in a process variable is a BEARER value from an expression.
 */
public enum AuthType {
    NONE,
    BEARER,
    BASIC,
    API_KEY,
    /** A header with a configurable name and value. */
    CUSTOM_HEADER,
    /** Client certificate and PKCS#8 or PKCS#1 private key, both PEM. */
    MTLS,
    /** HmacSHA256 of method, path, and body. */
    HMAC,
    AWS_SIG_V4
}
