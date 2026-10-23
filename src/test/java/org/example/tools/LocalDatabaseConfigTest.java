package org.example.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the local database configuration is read from the single source of truth
 * {@code classpath:db-local.properties}, which the application imports as well.
 */
class LocalDatabaseConfigTest {

    @Test
    @DisplayName("reads the defaults from db-local.properties")
    void readsDefaults() {
        LocalDatabaseConfig config = LocalDatabaseConfig.load();

        assertThat(config.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/appdb");
        assertThat(config.username()).isEqualTo("app");
        assertThat(config.password()).isEqualTo("app");
    }

    @Test
    @DisplayName("derives host, port and database from the JDBC URL")
    void derivesParts() {
        LocalDatabaseConfig config = LocalDatabaseConfig.load();

        assertThat(config.host()).isEqualTo("localhost");
        assertThat(config.port()).isEqualTo(5432);
        assertThat(config.database()).isEqualTo("appdb");
    }

    @Test
    @DisplayName("builds the URL of another database on the same server")
    void buildsUrlOfAnotherDatabase() {
        LocalDatabaseConfig config = LocalDatabaseConfig.load();

        assertThat(config.jdbcUrlOf("postgres")).isEqualTo("jdbc:postgresql://localhost:5432/postgres");
    }

    @Test
    @DisplayName("uses the default port when the URL does not contain one")
    void defaultPort() {
        LocalDatabaseConfig config = new LocalDatabaseConfig("jdbc:postgresql://localhost/appdb", "app", "app");

        assertThat(config.port()).isEqualTo(5432);
        assertThat(config.database()).isEqualTo("appdb");
    }

    @Test
    @DisplayName("ignores the query parameters of the JDBC URL")
    void ignoresQueryParameters() {
        LocalDatabaseConfig config = new LocalDatabaseConfig(
                "jdbc:postgresql://db.example.com:6543/appdb?ssl=true", "app", "app");

        assertThat(config.host()).isEqualTo("db.example.com");
        assertThat(config.port()).isEqualTo(6543);
        assertThat(config.database()).isEqualTo("appdb");
    }

    @Test
    @DisplayName("rejects a JDBC URL of an unsupported driver")
    void rejectsUnsupportedDriver() {
        LocalDatabaseConfig config = new LocalDatabaseConfig("jdbc:mysql://localhost:3306/appdb", "app", "app");

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, config::host))
                .hasMessageContaining("jdbc:mysql");
    }

    @Test
    @DisplayName("recognises the local host, so that the launcher knows what it may start")
    void recognisesLocalHost() {
        assertThat(LocalDatabaseConfig.load().isLocalHost()).isTrue();
        assertThat(new LocalDatabaseConfig("jdbc:postgresql://127.0.0.1:5432/appdb", "app", "app")
                .isLocalHost()).isTrue();
        assertThat(new LocalDatabaseConfig("jdbc:postgresql://db.example.com:5432/appdb", "app", "app")
                .isLocalHost()).isFalse();
    }
}
