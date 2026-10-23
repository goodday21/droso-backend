package org.example.health;

import java.time.Clock;
import java.time.Instant;

import org.example.health.model.DatabaseHealth;
import org.example.health.model.HealthResponse;
import org.springframework.stereotype.Service;

/**
 * Turns the raw result of the database probe into the public health response.
 *
 * <p>The service guarantees that a response can always be produced: unexpected failures of the
 * indicator are converted into a "DOWN" answer instead of an HTTP 500.
 */
@Service
public class HealthService {

    private final DatabaseHealthIndicator databaseHealthIndicator;
    private final Clock clock;

    public HealthService(DatabaseHealthIndicator databaseHealthIndicator, Clock clock) {
        this.databaseHealthIndicator = databaseHealthIndicator;
        this.clock = clock;
    }

    /**
     * Runs the check and builds the response.
     *
     * @return response with status UP or DOWN, never an exception
     */
    public HealthResponse check() {
        DatabaseHealth database;
        try {
            database = databaseHealthIndicator.check();
        } catch (RuntimeException e) {
            // Safety net: a health endpoint must always answer with a defined status.
            database = DatabaseHealth.down(0L);
        }
        return HealthResponse.of(database, Instant.now(clock));
    }
}