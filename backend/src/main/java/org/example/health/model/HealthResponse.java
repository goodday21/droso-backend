package org.example.health.model;

import java.time.Instant;

/**
 * Public response body of {@code GET /api/v1/health}.
 *
 * @param status    overall status of the service
 * @param database  status of the PostgreSQL dependency
 * @param timestamp moment the check was performed (ISO-8601, UTC)
 */
public record HealthResponse(HealthStatus status, DatabaseHealth database, Instant timestamp) {

    public static HealthResponse of(DatabaseHealth database, Instant timestamp) {
        return new HealthResponse(database.isUp() ? HealthStatus.UP : HealthStatus.DOWN, database, timestamp);
    }
}