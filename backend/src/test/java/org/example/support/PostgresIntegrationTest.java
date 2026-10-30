package org.example.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Supplier;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base of the Spring tests that need a real database: all of them share one PostgreSQL 14 server,
 * started in-process (zonky embedded-postgres, no Docker) on a free port by the first test class
 * and stopped by the shutdown hook of zonky when the JVM of the tests exits. The datasource of the
 * application context points to that server, whose schema is created by the Flyway migrations on
 * the first start-up of a context.
 *
 * <p>The subclass still declares {@code @SpringBootTest} itself, with the web environment it needs.
 * Since the datasource properties are the same for every subclass, Spring's context cache hands
 * the next test class of the same configuration the context of a previous one instead of starting
 * a new application: this sharing is what keeps the tests fast.
 *
 * <p>Every test starts with empty tables: {@link #truncateTables()} empties all the tables of the
 * schema except the history of Flyway and the reference data that the migrations insert
 * ({@code roles}). A new table is therefore emptied without being listed anywhere.
 *
 * <p>A test of this class must never stop the server: the following test classes would find it
 * stopped. A test that needs to stop it extends {@link IsolatedPostgresIntegrationTest}.
 */
public abstract class PostgresIntegrationTest {

    /** Tables that the migrations fill and that no test changes. */
    private static final List<String> KEPT_TABLES = List.of("flyway_schema_history", "roles");

    private static EmbeddedPostgres postgres;

    /** The shared server, started on the first call. */
    protected static synchronized EmbeddedPostgres postgres() {
        if (postgres == null) {
            try {
                postgres = startServer();
            } catch (IOException e) {
                throw new UncheckedIOException("The embedded PostgreSQL server did not start", e);
            }
        }
        return postgres;
    }

    /**
     * Starts a server on a free port that reports its messages in English.
     *
     * <p>The server inherits the OS locale on Windows and therefore answers in Russian. "C" keeps
     * every server-side message in English.
     */
    public static EmbeddedPostgres startServer() throws IOException {
        return EmbeddedPostgres.builder()
                .setPort(0)
                .setServerConfig("lc_messages", "C")
                .start();
    }

    /** The datasource properties of the application, pointing to the server that {@code server} gives. */
    static void registerDatasource(DynamicPropertyRegistry registry, Supplier<EmbeddedPostgres> server) {
        registry.add("spring.datasource.url", () -> server.get().getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        // The application does not start without a secret of the access tokens; a test may forge
        // tokens with it (TestUsers.JWT_SECRET).
        registry.add("droso.jwt.secret", () -> TestUsers.JWT_SECRET);
        // No administrator is created on start-up, whatever the developer has in DROSO_BOOTSTRAP_ADMIN_*:
        // the tables are emptied before every test anyway, and a taken email would stop the context.
        // BootstrapAdminIntegrationTest calls the service with its own settings.
        registry.add("droso.bootstrap.admin.email", () -> "");
        registry.add("droso.bootstrap.admin.password", () -> "");
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registerDatasource(registry, PostgresIntegrationTest::postgres);
    }

    // Named apart from the usual cleanUp/clearDatabase, so that a subclass declaring a method of
    // that name does not silently hide this one. It runs before the @BeforeEach of the subclass.
    @BeforeEach
    void truncateTables() {
        JdbcTemplate jdbc = new JdbcTemplate(postgres().getPostgresDatabase());
        List<String> tables = jdbc.queryForList(
                "SELECT format('%I', tablename) FROM pg_tables WHERE schemaname = 'public'"
                        + " AND NOT tablename = ANY (?)", String.class, (Object) KEPT_TABLES.toArray(String[]::new));
        if (!tables.isEmpty()) {
            jdbc.execute("TRUNCATE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
        }
    }
}
