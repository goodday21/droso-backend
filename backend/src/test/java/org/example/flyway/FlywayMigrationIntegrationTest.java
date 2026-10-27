package org.example.flyway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.example.support.PostgresIntegrationTest;
import org.example.user.Role;
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
    @DisplayName("a clean database gets every migration, and keeps the baseline table")
    void cleanDatabaseGetsEveryMigration() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true", String.class);
        Integer failed = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = false", Integer.class);

        assertThat(versions).contains("1", "2", "3", "4");
        assertThat(failed).isZero();
        assertThat(tableExists(jdbc, "demo_item")).isTrue();
        assertThat(tableExists(jdbc, "defects")).isTrue();
        assertThat(tableExists(jdbc, "users")).isTrue();
        assertThat(tableExists(jdbc, "roles")).isTrue();
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
        assertThat(tableExists(jdbc, "users")).isTrue();
        assertThat(jdbc.queryForList("SELECT name FROM demo_item", String.class)).containsExactly("kept");
        assertThat(jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true", String.class))
                .contains("1", "2", "3", "4");
    }

    @Test
    @DisplayName("the users migration is applied on top of the defects migration and keeps the defects")
    void upgradeFromDefects() throws Exception {
        new JdbcTemplate(postgres().getPostgresDatabase()).execute("CREATE DATABASE upgrade_from_defects");
        DataSource upgraded = postgres().getDatabase("postgres", "upgrade_from_defects");
        JdbcTemplate jdbc = new JdbcTemplate(upgraded);

        Flyway.configure().dataSource(upgraded).locations("classpath:db/migration").target("2").load().migrate();
        assertThat(tableExists(jdbc, "users")).isFalse();
        jdbc.update("INSERT INTO defects (title, created_at, updated_at) VALUES ('Kept defect', now(), now())");

        Flyway.configure().dataSource(upgraded).locations("classpath:db/migration").load().migrate();

        assertThat(tableExists(jdbc, "users")).isTrue();
        assertThat(jdbc.queryForList("SELECT title FROM defects", String.class)).containsExactly("Kept defect");
    }

    @Test
    @DisplayName("the widened password hash keeps the stored users and fits an Argon2id hash")
    void upgradeWidensPasswordHash() throws Exception {
        new JdbcTemplate(postgres().getPostgresDatabase()).execute("CREATE DATABASE upgrade_password_hash");
        DataSource upgraded = postgres().getDatabase("postgres", "upgrade_password_hash");
        JdbcTemplate jdbc = new JdbcTemplate(upgraded);
        String bcrypt = "{bcrypt}$2a$12$" + "s".repeat(22) + "h".repeat(31);
        // The format of Spring's Argon2PasswordEncoder with its default parameters: 105 characters.
        String argon2 = "{argon2}$argon2id$v=19$m=16384,t=2,p=1$" + "s".repeat(22) + "$" + "h".repeat(43);
        String insert = "INSERT INTO users (email, password_hash, role_code, created_at, updated_at)"
                + " VALUES (?, ?, 'USER', now(), now())";

        Flyway.configure().dataSource(upgraded).locations("classpath:db/migration").target("3").load().migrate();
        jdbc.update(insert, "kept@b.cd", bcrypt);
        assertThatThrownBy(() -> jdbc.update(insert, "argon@b.cd", argon2))
                .isInstanceOf(DataIntegrityViolationException.class);

        Flyway.configure().dataSource(upgraded).locations("classpath:db/migration").load().migrate();

        assertThat(argon2).hasSize(105);
        jdbc.update(insert, "argon@b.cd", argon2);
        assertThat(jdbc.queryForList("SELECT password_hash FROM users ORDER BY id", String.class))
                .containsExactly(bcrypt, argon2);
        assertThatThrownBy(() -> jdbc.update(insert, "long@b.cd", "x".repeat(256)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the users table has the expected columns, types, lengths and nullability")
    void usersColumns() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        List<String> columns = jdbc.query("""
                SELECT column_name, data_type, character_maximum_length, is_nullable, is_identity
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'users'
                ORDER BY ordinal_position
                """,
                (rs, row) -> String.join(" ", rs.getString("column_name"), rs.getString("data_type"),
                        String.valueOf(rs.getObject("character_maximum_length")),
                        rs.getString("is_nullable"), rs.getString("is_identity")));

        assertThat(columns).containsExactly(
                "id bigint null NO YES",
                "email character varying 254 NO NO",
                "password_hash character varying 255 NO NO",
                "role_code character varying 32 NO NO",
                "deleted_at timestamp with time zone null YES NO",
                "created_at timestamp with time zone null NO NO",
                "updated_at timestamp with time zone null NO NO");
    }

    @Test
    @DisplayName("the roles table holds exactly the values of the Role enum")
    void rolesMatchTheEnum() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        List<String> codes = jdbc.queryForList("SELECT code FROM roles", String.class);

        assertThat(codes).containsExactlyInAnyOrder(
                Arrays.stream(Role.values()).map(Role::name).toArray(String[]::new));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO users (email, password_hash, role_code, created_at, updated_at) VALUES ('a@b.cd', 'h', 'ROOT', now(), now())",
            "INSERT INTO users (email, password_hash, created_at, updated_at) VALUES ('a@b.cd', 'h', now(), now())",
            "INSERT INTO users (email, password_hash, role_code, created_at, updated_at) VALUES ('A@b.cd', 'h', 'USER', now(), now())",
            "INSERT INTO users (email, password_hash, role_code, created_at, updated_at) VALUES (NULL, 'h', 'USER', now(), now())",
            "INSERT INTO users (email, password_hash, role_code, created_at, updated_at) VALUES ('a@b.cd', NULL, 'USER', now(), now())",
            "INSERT INTO users (email, password_hash, role_code, updated_at) VALUES ('a@b.cd', 'h', 'USER', now())",
            "INSERT INTO users (email, password_hash, role_code, created_at) VALUES ('a@b.cd', 'h', 'USER', now())"
    })
    @DisplayName("the users table rejects rows that violate its constraints")
    void usersConstraints(String insert) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThatThrownBy(() -> jdbc.update(insert)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an email is unique among the active users only")
    void emailUniqueAmongActiveUsers() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String insert = "INSERT INTO users (email, password_hash, role_code, deleted_at, created_at, updated_at)"
                + " VALUES ('unique@b.cd', 'h', 'USER', ?, now(), now())";
        try {
            jdbc.update(insert, (Object) null);
            assertThatThrownBy(() -> jdbc.update(insert, (Object) null))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("users_email_active_key");

            // Deleted users with the same email may exist next to the active one.
            jdbc.update(insert, Timestamp.from(Instant.now()));
            jdbc.update(insert, Timestamp.from(Instant.now()));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = 'unique@b.cd'", Integer.class))
                    .isEqualTo(3);
        } finally {
            jdbc.update("DELETE FROM users WHERE email = 'unique@b.cd'");
        }
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