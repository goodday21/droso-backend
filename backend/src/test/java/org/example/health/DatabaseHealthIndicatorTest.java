package org.example.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.example.health.model.DatabaseHealth;
import org.example.health.model.HealthStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests of the database probe. No real database is required.
 */
class DatabaseHealthIndicatorTest {

    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private ResultSet resultSet;
    private DatabaseHealthIndicator indicator;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        resultSet = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);

        indicator = new DatabaseHealthIndicator(dataSource, new HealthProperties(2, "SELECT 1"));
    }

    @Nested
    @DisplayName("database is available")
    class Available {

        @Test
        @DisplayName("reports UP when the probe query returns a row")
        void reportsUp() {
            DatabaseHealth result = indicator.check();

            assertThat(result.status()).isEqualTo(HealthStatus.UP);
            assertThat(result.isUp()).isTrue();
            assertThat(result.latencyMs()).isNotNegative();
        }

        @Test
        @DisplayName("executes exactly the configured probe query")
        void executesProbeQuery() throws SQLException {
            indicator.check();

            verify(statement).executeQuery("SELECT 1");
        }

        @Test
        @DisplayName("applies a query timeout so a hanging server cannot block the endpoint")
        void appliesQueryTimeout() throws SQLException {
            indicator.check();

            verify(statement).setQueryTimeout(anyInt());
        }

        @Test
        @DisplayName("closes connection and statement to avoid leaking pool entries")
        void closesResources() throws SQLException {
            indicator.check();

            verify(connection).close();
            verify(statement).close();
        }

        @Test
        @DisplayName("does not touch any table")
        void doesNotQueryTables() throws SQLException {
            indicator.check();

            verify(statement, never()).executeQuery("SELECT * FROM demo_item");
        }
    }

    @Nested
    @DisplayName("database is not available")
    class NotAvailable {

        @Test
        @DisplayName("reports DOWN when the connection cannot be obtained")
        void connectionRefused() throws SQLException {
            when(dataSource.getConnection()).thenThrow(new SQLException("Connection refused"));

            DatabaseHealth result = indicator.check();

            assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
            assertThat(result.isUp()).isFalse();
        }

        @Test
        @DisplayName("reports DOWN when the statement cannot be created")
        void statementFails() throws SQLException {
            when(connection.createStatement()).thenThrow(new SQLException("cannot create statement"));

            assertThat(indicator.check().status()).isEqualTo(HealthStatus.DOWN);
        }

        @Test
        @DisplayName("reports DOWN when the probe query fails")
        void queryFails() throws SQLException {
            when(statement.executeQuery(anyString())).thenThrow(new SQLException("connection reset"));

            assertThat(indicator.check().status()).isEqualTo(HealthStatus.DOWN);
        }

        @Test
        @DisplayName("reports DOWN when the server answers without a row")
        void queryReturnsNoRow() throws SQLException {
            when(resultSet.next()).thenReturn(false);

            assertThat(indicator.check().status()).isEqualTo(HealthStatus.DOWN);
        }

        @Test
        @DisplayName("reports DOWN on a query timeout")
        void queryTimesOut() throws SQLException {
            when(statement.executeQuery(anyString())).thenThrow(new SQLTimeoutException("timeout"));

            assertThat(indicator.check().status()).isEqualTo(HealthStatus.DOWN);
        }

        @Test
        @DisplayName("reports DOWN on a runtime failure of the pool")
        void runtimeFailure() throws SQLException {
            when(dataSource.getConnection()).thenThrow(new IllegalStateException("pool closed"));

            assertThat(indicator.check().status()).isEqualTo(HealthStatus.DOWN);
        }

        @Test
        @DisplayName("a stale pooled connection must not be reported as UP")
        void staleConnectionIsNotUp() throws SQLException {
            // The pool hands out a connection successfully, but the round-trip to the server fails.
            when(statement.executeQuery(anyString())).thenThrow(new SQLException("server closed the connection"));

            assertThat(indicator.check().status()).isEqualTo(HealthStatus.DOWN);
        }
    }

    @Nested
    @DisplayName("no technical details are exposed")
    class NoLeakage {

        @ParameterizedTest
        @ValueSource(strings = {
                "jdbc:postgresql://10.0.0.5:5432/test_db",
                "org.postgresql.util.PSQLException",
                "Connection to 10.0.0.5:5432 refused. Check that the hostname and port are correct"
        })
        @DisplayName("the result object contains neither URL, driver class nor exception message")
        void resultIsSanitised(String secret) throws SQLException {
            when(statement.executeQuery(anyString())).thenThrow(new SQLException(secret));

            DatabaseHealth result = indicator.check();

            assertThat(result.toString()).doesNotContain("jdbc:", "org.postgresql", "10.0.0.5", "password");
        }
    }
}