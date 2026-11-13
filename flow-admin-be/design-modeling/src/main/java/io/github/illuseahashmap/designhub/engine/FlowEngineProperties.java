package io.github.illuseahashmap.designhub.engine;

import java.time.Duration;

/**
 * How Flow Admin BE reaches its Flow Engine.
 *
 * @param baseUrl      Flow Engine itself, without a path (a proxy that rewrites paths breaks the service token)
 * @param clientCode   service client registered in Flow Engine ({@code workflow_service_client})
 * @param clientSecret its shared secret
 * @param tokenVersion its {@code secret_version}
 * @param environment  name of the environment this Flow Engine runs, to pick the base URL of a catalog entry
 */
public record FlowEngineProperties(
        String baseUrl,
        String clientCode,
        String clientSecret,
        int tokenVersion,
        String environment,
        Duration connectTimeout,
        Duration readTimeout,
        Duration authCacheTtl) {

    public FlowEngineProperties {
        baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        environment = environment == null || environment.isBlank() ? "default" : environment;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(30) : readTimeout;
        authCacheTtl = authCacheTtl == null ? Duration.ofSeconds(60) : authCacheTtl;
    }
}
