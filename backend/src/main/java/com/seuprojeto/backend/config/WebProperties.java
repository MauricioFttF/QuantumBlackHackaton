package com.seuprojeto.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * HTTP-layer settings.
 *
 * @param corsAllowedOrigins origins allowed to call the API from a browser. Explicit list, not
 *                           a wildcard: the API is unauthenticated, so anything reachable can
 *                           spend AI quota.
 */
@ConfigurationProperties(prefix = "app.web")
public record WebProperties(List<String> corsAllowedOrigins) {

    public WebProperties {
        if (corsAllowedOrigins == null || corsAllowedOrigins.isEmpty()) {
            throw new IllegalArgumentException(
                    "app.web.cors-allowed-origins must list at least one origin");
        }
        if (corsAllowedOrigins.contains("*")) {
            throw new IllegalArgumentException(
                    "app.web.cors-allowed-origins must not be '*': the API has no authentication, "
                            + "so a wildcard lets any site on the internet spend the AI quota.");
        }
        corsAllowedOrigins = List.copyOf(corsAllowedOrigins);
    }
}
