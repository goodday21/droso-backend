package org.example.flyway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.example.support.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies that the Flyway migrations are applied on start-up, both on a clean database and on top
 * of the baseline schema, and that the health probe does not depend on any migrated table.
 */
@SpringBootTest
class FlywayMigrationIntegrationTest extends PostgresIntegrationTest {
    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("the baseline migration is applied")
    void migrationIsApplied() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);

        assertThat(applied).isNotNull().isGreaterThan(0);
    }

    @Test
    @DisplayName("a clean database gets the baseline and the defects migration, and keeps the baseline table")
    void cleanDatabaseGetsEveryMigration() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true", String.class);
        Integer failed = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = false", Integer.class);

        assertThat(versions).contains("1", "2");
        assertThat(failed).isZero();
        assertThat(tableExists(jdbc, "demo_item")).isTrue();
        assertThat(tableExists(jdbc, "defects")).isTrue();
    }

    @Test
    @DisplayName("the defects table has the expected columns, types, lengths and nullability")
    void defectsColumns() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        List<String> columns = jdbc.query("""
                SELECT column_name, data_type, character_maximum_length, is_nullable, is_identity
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'defects'
                ORDER BY ordinal_position
                """,
                (rs, row) -> String.join(" ", rs.getString("column_name"), rs.getString("data_type"),
                        String.valueOf(rs.getObject("character_maximum_length")),
                        rs.getString("is_nullable"), rs.getString("is_identity")));

        assertThat(columns).containsExactly(
                "id bigint null NO YES",
                "title character varying 120 NO NO",
                "description character varying 2000 YES NO",
                "steps_to_reproduce character varying 5000 YES NO",
                "expected_result character varying 2000 YES NO",
                "actual_result character varying 2000 YES NO",
                "priority character varying 16 NO NO",
                "status character varying 16 NO NO",
                "created_at timestamp with time zone null NO NO",
                "updated_at timestamp with time zone null NO NO");
    }

    @Test
    @DisplayName("the defects table defaults the priority to MEDIUM and the status to NEW")
    void defectsDefaults() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Map<String, Object> row = jdbc.queryForMap("""
                INSERT INTO defects (title, created_at, updated_at) VALUES ('Defaults', now(), now())
                RETURNING priority, status
                """);

        assertThat(row.get("priority")).isEqualTo("MEDIUM");
        assertThat(row.get("status")).isEqualTo("NEW");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO defects (title, priority, created_at, updated_at) VALUES ('Unknown', 'URGENT', now(), now())",
            "INSERT INTO defects (title, status, created_at, updated_at) VALUES ('Unknown', 'CLOSED', now(), now())",
            "INSERT INTO defects (title, created_at, updated_at) VALUES (NULL, now(), now())",
            "INSERT INTO defects (title, created_at, updated_at) VALUES (repeat('a', 121), now(), now())",
            "INSERT INTO defects (title, updated_at) VALUES ('No creation time', now())",
            "INSERT INTO defects (title, created_at) VALUES ('No update time', now())"
    })
    @DisplayName("the defects table rejects rows that violate its constraints")
    void defectsConstraints(String insert) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThatThrownBy(() -> jdbc.update(insert)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the defects migration is applied on top of an existing baseline schema and keeps its data")
    void upgradeFromBaseline() throws Exception {
        new JdbcTemplate(postgres().getPostgresDatabase()).execute("CREATE DATABASE upgrade_path");
        DataSource upgraded = postgres().getDatabase("postgres", "upgrade_path");
        JdbcTemplate jdbc = new JdbcTemplate(upgraded);

        Flyway.configure().dataSource(upgraded).locations("classpath:db/migration").target("1").load().migrate();
        assertThat(tableExists(jdbc, "defects")).isFalse();
        jdbc.update("INSERT INTO demo_item (name) VALUES ('kept')");

        Flyway.configure().dataSource(upgraded).locations("classpath:db/migration").load().migrate();

        assertThat(tableExists(jdbc, "defects")).isTrue();
        assertThat(jdbc.queryForList("SELECT name FROM demo_item", String.class)).containsExactly("kept");
        assertThat(jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true", String.class))
                .contains("1", "2");
    }

    private static boolean tableExists(JdbcTemplate jdbc, String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT to_regclass(?) IS NOT NULL", Boolean.class, "public." + table));
    }

    @Test
    @DisplayName("the probe query works without touching any table")
    void probeQueryIsTableIndependent() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Integer value = jdbc.queryForObject("SELECT 1", Integer.class);

        assertThat(value).isEqualTo(1);
    }
}