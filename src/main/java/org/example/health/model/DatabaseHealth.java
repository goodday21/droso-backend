package org.example.health.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Health of the PostgreSQL database as observed by a single check.
 *
 * <p>Deliberately contains no technical details (no JDBC URL, host, port, database name, user,
 * exception message or stack trace). Such information may only be written to the server-side log.
 *
 * @param status    result of the check
 * @param latencyMs wall-clock duration of the check in milliseconds
 */
public record DatabaseHealth(HealthStatus status, long latencyMs) {

    public static DatabaseHealth up(long latencyMs) {
        return new DatabaseHealth(HealthStatus.UP, latencyMs);
    }

    public static DatabaseHealth down(long latencyMs) {
        return new DatabaseHealth(HealthStatus.DOWN, latencyMs);
    }

    /**
     * Convenience accessor for the internal logic. It is not part of the public JSON contract.
     */
    @JsonIgnore
    public boolean isUp() {
        return status == HealthStatus.UP;
    }
}