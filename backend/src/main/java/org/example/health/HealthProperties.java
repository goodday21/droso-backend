package org.example.health;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunable parameters of the health check.
 *
 * @param timeoutSeconds server-side timeout (in seconds) of the probe query
 * @param query          the probe statement executed against PostgreSQL
 */
@ConfigurationProperties(prefix = "health")
public record HealthProperties(int timeoutSeconds, String query) {

    public HealthProperties {
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 2;
        }
        if (query == null || query.isBlank()) {
            query = "SELECT 1";
        }
    }
}