package org.example.flyway;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Verifies that the Flyway migrations are applied on start-up and that the health probe does not
 * depend on any migrated table.
 */
@SpringBootTest
class FlywayMigrationIntegrationTest {

    private static EmbeddedPostgres postgres;

    @Autowired
    private DataSource dataSource;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder()
                .setPort(0)
                // The server inherits the OS locale on Windows and therefore answers in Russian.
                // "C" keeps every server-side message in English.
                .setServerConfig("lc_messages", "C")
                .start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    @Test
    @DisplayName("the baseline migration is applied")
    void migrationIsApplied() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);

        assertThat(applied).isNotNull().isGreaterThan(0);
    }

    @Test
    @DisplayName("the probe query works without touching any table")
    void probeQueryIsTableIndependent() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Integer value = jdbc.queryForObject("SELECT 1", Integer.class);

        assertThat(value).isEqualTo(1);
    }
}