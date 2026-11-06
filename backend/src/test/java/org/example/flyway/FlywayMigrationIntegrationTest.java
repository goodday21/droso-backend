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

        assertThat(versions).contains("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        assertThat(failed).isZero();
        assertThat(tableExists(jdbc, "demo_item")).isTrue();
        assertThat(tableExists(jdbc, "tickets")).isTrue();
        assertThat(tableExists(jdbc, "users")).isTrue();
        assertThat(tableExists(jdbc, "roles")).isTrue();
    }

    @Test
    @DisplayName("the tickets table has the expected columns, types, lengths and nullability")
    void ticketsColumns() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        List<String> columns = jdbc.query("""
                SELECT column_name, data_type, character_maximum_length, is_nullable, is_identity
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'tickets'
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
                "updated_at timestamp with time zone null NO NO",
                "created_by bigint null YES NO",
                "type character varying 16 NO NO",
                "deleted_at timestamp with time zone null YES NO");
    }

    @Test
    @DisplayName("tickets.created_by refers to users and is indexed")
    void ticketsCreatedBy() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        String foreignKey = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conrelid = 'tickets'::regclass AND conname = 'tickets_created_by_fkey'
                """, String.class);
        String index = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'tickets' AND indexname = 'tickets_created_by_idx'",
                String.class);

        assertThat(foreignKey).isEqualTo("FOREIGN KEY (created_by) REFERENCES users(id)");
        assertThat(index).endsWith("USING btree (created_by)");
    }

    @Test
    @DisplayName("the tickets table defaults the priority to MEDIUM, the status to NEW, the type to FEATURE and keeps a ticket active")
    void ticketsDefaults() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Map<String, Object> row = jdbc.queryForMap("""
                INSERT INTO tickets (title, created_at, updated_at) VALUES ('Defaults', now(), now())
                RETURNING priority, status, type, deleted_at
                """);

        assertThat(row.get("priority")).isEqualTo("MEDIUM");
        assertThat(row.get("status")).isEqualTo("NEW");
        assertThat(row.get("type")).isEqualTo("FEATURE");
        assertThat(row.get("deleted_at")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO tickets (title, priority, created_at, updated_at) VALUES ('Unknown', 'URGENT', now(), now())",
            "INSERT INTO tickets (title, status, created_at, updated_at) VALUES ('Unknown', 'CLOSED', now(), now())",
            "INSERT INTO tickets (title, status, created_at, updated_at) VALUES ('Unknown', 'WAITING_FOR_FIXES', now(), now())",
            "INSERT INTO tickets (title, status, created_at, updated_at) VALUES ('Unknown', 'new', now(), now())",
            "INSERT INTO tickets (title, type, created_at, updated_at) VALUES ('Unknown', 'TASK', now(), now())",
            "INSERT INTO tickets (title, type, created_at, updated_at) VALUES ('Unknown', NULL, now(), now())",
            "INSERT INTO tickets (title, created_at, updated_at) VALUES (NULL, now(), now())",
            "INSERT INTO tickets (title, created_at, updated_at) VALUES (repeat('a', 121), now(), now())",
            "INSERT INTO tickets (title, updated_at) VALUES ('No creation time', now())",
            "INSERT INTO tickets (title, created_at) VALUES ('No update time', now())"
    })
    @DisplayName("the tickets table rejects rows that violate its constraints")
    void ticketsConstraints(String insert) {
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

        assertThat(tableExists(jdbc, "tickets")).isTrue();
        assertThat(tableExists(jdbc, "users")).isTrue();
        assertThat(jdbc.queryForList("SELECT name FROM demo_item", String.class)).containsExactly("kept");
        assertThat(jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true", String.class))
                .contains("1", "2", "3", "4", "5");
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
        assertThat(jdbc.queryForList("SELECT title FROM tickets", String.class)).containsExactly("Kept defect");
    }

    @Test
    @DisplayName("the type and the soft deletion of the defects keep the stored defects as active features")
    void upgradeAddsDefectTypeAndDeletion() throws Exception {
        new JdbcTemplate(postgres().getPostgresDatabase()).execute("CREATE DATABASE upgrade_defect_type");
        DataSource upgraded = postgres().getDatabase("postgres", "upgrade_defect_type");
        JdbcTemplate jdbc = new JdbcTemplate(upgraded);

        Flyway.configure().dataSource(upgraded).locations("classpath:db/migration").target("8").load().migrate();
        jdbc.update("INSERT INTO defects (title, created_at, updated_at) VALUES ('Kept defect', now(), now())");

        Flyway.configure().dataSource(upgraded).locations("classpath:db/migration").load().migrate();

        Map<String, Object> row = jdbc.queryForMap("SELECT title, status, type, deleted_at FROM tickets");
        assertThat(row.get("title")).isEqualTo("Kept defect");
        assertThat(row.get("status")).isEqualTo("NEW");
        assertThat(row.get("type")).isEqualTo("FEATURE");
        assertThat(row.get("deleted_at")).isNull();
    }

    @Test
    @DisplayName("the rename to tickets keeps the stored defects, their ids and the identity, and leaves no defects_ object")
    void upgradeRenamesDefectsToTickets() throws Exception {
        new JdbcTemplate(postgres().getPostgresDatabase()).execute("CREATE DATABASE upgrade_tickets");
        DataSource upgraded = postgres().getDatabase("postgres", "upgrade_tickets");
        JdbcTemplate jdbc = new JdbcTemplate(upgraded);

        Flyway.configure().dataSource(upgraded).locations("classpath:db/migration").target("9").load().migrate();
        jdbc.update("INSERT INTO defects (title, type, created_at, updated_at) VALUES ('Kept bug', 'BUG', now(), now())");
        jdbc.update("INSERT INTO defects (title, created_at, updated_at) VALUES ('Kept feature', now(), now())");

        Flyway.configure().dataSource(upgraded).locations("classpath:db/migration").load().migrate();

        assertThat(tableExists(jdbc, "defects")).isFalse();
        assertThat(jdbc.queryForList("SELECT id || ' ' || title || ' ' || type FROM tickets ORDER BY id", String.class))
                .containsExactly("1 Kept bug BUG", "2 Kept feature FEATURE");
        // The identity goes on where the defects stopped.
        assertThat(jdbc.queryForObject(
                "INSERT INTO tickets (title, created_at, updated_at) VALUES ('New ticket', now(), now()) RETURNING id",
                Long.class)).isEqualTo(3L);
        // Tables, sequences, indexes and constraints: nothing is named after the old table any more.
        assertThat(jdbc.queryForList("""
                SELECT relname FROM pg_class WHERE relnamespace = 'public'::regnamespace AND relname LIKE 'defects%'
                UNION ALL
                SELECT conname FROM pg_constraint WHERE connamespace = 'public'::regnamespace AND conname LIKE 'defects%'
                """, String.class)).isEmpty();
        assertThat(jdbc.queryForList("SELECT description FROM roles ORDER BY code", String.class)).containsExactly(
                "Reads, creates and edits the tickets, manages the users",
                "Reads, creates and edits the tickets",
                "Reads and creates the tickets");
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
                "updated_at timestamp with time zone null NO NO",
                "token_version integer null NO NO");
    }

    @Test
    @DisplayName("the email has the collation C, so that the list sorted by email is alphabetical on every server")
    void usersEmailCollation() {
        // The test servers are created under LC_ALL=C, where the default order is the same: only the
        // collation of the column shows that a server with another locale sorts the same way.
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(jdbc.queryForObject("""
                SELECT collation_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'email'
                """, String.class)).isEqualTo("C");
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