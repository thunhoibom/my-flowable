package io.github.illuseahashmap.serviceregistry.domain;

/** Extra fields of resolved credentials for MTLS, HMAC and AWS SigV4. */
public record AuthDetails(
        String certificatePem,
        String accessKey,
        String region,
        String awsService,
        String signatureHeader) {

    public static AuthDetails empty() {
        return new AuthDetails(null, null, null, null, null);
    }
}
