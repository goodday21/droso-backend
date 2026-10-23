package org.example.health;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.example.health.model.DatabaseHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Performs the real availability check of PostgreSQL.
 *
 * <p>The check deliberately executes a trivial statement ({@code SELECT 1}) so that the server
 * actually processes the request. Merely obtaining a {@link Connection} from the pool is not
 * enough: a pooled connection may be stale while the database is already down, which would
 * produce a false "UP".
 *
 * <p>This component never throws: any failure is converted into {@link HealthStatus#DOWN}.
 */
@Component
public class DatabaseHealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(DatabaseHealthIndicator.class);

    private final DataSource dataSource;
    private final HealthProperties properties;

    public DatabaseHealthIndicator(DataSource dataSource, HealthProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    /**
     * Executes the probe query against PostgreSQL.
     *
     * @return {@link HealthStatus#UP} only if the server really answered the probe query
     */
    public DatabaseHealth check() {
        long startNanos = System.nanoTime();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {

            statement.setQueryTimeout(properties.timeoutSeconds());
            try (ResultSet resultSet = statement.executeQuery(properties.query())) {
                // The probe must actually produce a row, otherwise the server did not answer as expected.
                if (!resultSet.next()) {
                    log.warn("Database health check failed: probe query returned no rows");
                    return DatabaseHealth.down(elapsedMs(startNanos));
                }
            }
            return DatabaseHealth.up(elapsedMs(startNanos));
        } catch (SQLException | RuntimeException e) {
            // Only the exception type is logged: the message of a JDBC exception usually contains
            // the JDBC URL, host, port and user name, which must not leak into the HTTP response.
            log.warn("Database health check failed: type={}, elapsedMs={}",
                    e.getClass().getSimpleName(), elapsedMs(startNanos));
            log.debug("Database health check failure details", e);
            return DatabaseHealth.down(elapsedMs(startNanos));
        }
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}