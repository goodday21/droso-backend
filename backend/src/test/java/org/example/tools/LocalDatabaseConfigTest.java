package org.example.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies how the local database configuration is built from the keys of
 * {@code db-local.properties}, which the application imports as well.
 *
 * <p>The tests use fixed values instead of the file on the classpath: that file is the developer's
 * own working copy (or the example on a fresh clone and in CI), so its values differ per machine.
 */
class LocalDatabaseConfigTest {

    private static final Map<String, String> NO_ENVIRONMENT = Map.of();

    private static Properties properties() {
        Properties properties = new Properties();
        properties.setProperty("POSTGRES_HOST", "localhost");
        properties.setProperty("POSTGRES_PORT", "5432");
        properties.setProperty("POSTGRES_DB", "test_db");
        properties.setProperty("POSTGRES_USER", "test_user");
        properties.setProperty("POSTGRES_PASSWORD", "test_password");
        return properties;
    }

    @Test
    @DisplayName("composes the URL and the credentials from the properties")
    void composesFromProperties() {
        LocalDatabaseConfig config = LocalDatabaseConfig.from(properties(), NO_ENVIRONMENT);

        assertThat(config.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/test_db");
        assertThat(config.username()).isEqualTo("test_user");
        assertThat(config.password()).isEqualTo("test_password");
    }

    @Test
    @DisplayName("the environment variables override the properties")
    void environmentOverrides() {
        LocalDatabaseConfig config = LocalDatabaseConfig.from(properties(), Map.of(
                "DB_URL", "jdbc:postgresql://db.example.com:6543/other",
                "DB_USERNAME", "someone",
                "DB_PASSWORD", "else"));

        assertThat(config.jdbcUrl()).isEqualTo("jdbc:postgresql://db.example.com:6543/other");
        assertThat(config.username()).isEqualTo("someone");
        assertThat(config.password()).isEqualTo("else");

        assertThat(LocalDatabaseConfig.from(properties(), Map.of("POSTGRES_PORT", "55432")).port())
                .isEqualTo(55432);
    }

    @Test
    @DisplayName("loads the configuration of the classpath")
    void loadsFromClasspath() {
        // Only the shape is checked: the values belong to the developer's own file.
        LocalDatabaseConfig config = LocalDatabaseConfig.load();

        assertThat(config.jdbcUrl()).startsWith("jdbc:postgresql://");
        assertThat(config.username()).isNotBlank();
    }

    @Test
    @DisplayName("derives host, port and database from the JDBC URL")
    void derivesParts() {
        LocalDatabaseConfig config = LocalDatabaseConfig.from(properties(), NO_ENVIRONMENT);

        assertThat(config.host()).isEqualTo("localhost");
        assertThat(config.port()).isEqualTo(5432);
        assertThat(config.database()).isEqualTo("test_db");
    }

    @Test
    @DisplayName("builds the URL of another database on the same server")
    void buildsUrlOfAnotherDatabase() {
        LocalDatabaseConfig config = LocalDatabaseConfig.from(properties(), NO_ENVIRONMENT);

        assertThat(config.jdbcUrlOf("postgres")).isEqualTo("jdbc:postgresql://localhost:5432/postgres");
    }

    @Test
    @DisplayName("uses the default port when the URL does not contain one")
    void defaultPort() {
        LocalDatabaseConfig config = new LocalDatabaseConfig("jdbc:postgresql://localhost/test_db", "test_user", "test_password");

        assertThat(config.port()).isEqualTo(5432);
        assertThat(config.database()).isEqualTo("test_db");
    }

    @Test
    @DisplayName("ignores the query parameters of the JDBC URL")
    void ignoresQueryParameters() {
        LocalDatabaseConfig config = new LocalDatabaseConfig(
                "jdbc:postgresql://db.example.com:6543/test_db?ssl=true", "test_user", "test_password");

        assertThat(config.host()).isEqualTo("db.example.com");
        assertThat(config.port()).isEqualTo(6543);
        assertThat(config.database()).isEqualTo("test_db");
    }

    @Test
    @DisplayName("rejects a JDBC URL of an unsupported driver")
    void rejectsUnsupportedDriver() {
        LocalDatabaseConfig config = new LocalDatabaseConfig("jdbc:mysql://localhost:3306/test_db", "test_user", "test_password");

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, config::host))
                .hasMessageContaining("jdbc:mysql");
    }

    @Test
    @DisplayName("recognises the local host, so that the launcher knows what it may start")
    void recognisesLocalHost() {
        assertThat(LocalDatabaseConfig.from(properties(), NO_ENVIRONMENT).isLocalHost()).isTrue();
        assertThat(new LocalDatabaseConfig("jdbc:postgresql://127.0.0.1:5432/test_db", "test_user", "test_password")
                .isLocalHost()).isTrue();
        assertThat(new LocalDatabaseConfig("jdbc:postgresql://db.example.com:5432/test_db", "test_user", "test_password")
                .isLocalHost()).isFalse();
    }
}
