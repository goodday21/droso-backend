package org.example.support;

import java.io.IOException;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base of the Spring tests that need a real database: every test class gets its own PostgreSQL 14
 * server, started in-process (zonky embedded-postgres, no Docker) on a free port before the class
 * and stopped after it. The datasource of the application context points to that server, whose
 * schema is created by the Flyway migrations on start-up.
 *
 * <p>The subclass still declares {@code @SpringBootTest} itself, with the web environment it needs.
 * A test may stop the server ({@code postgres().close()}) to check how the application behaves
 * without a database; it is then stopped for the rest of the class.
 *
 * <p>{@code @DirtiesContext} is required, not a precaution: the {@code @DynamicPropertySource}
 * method is the same for every subclass, so Spring's context cache would hand the next test class
 * with the same configuration the context of the previous one, whose datasource points to a
 * server that has already been stopped. Closing the context after the class keeps one server and
 * one context per class.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class PostgresIntegrationTest {

    private static EmbeddedPostgres postgres;

    /** The server of the running test class. */
    protected static EmbeddedPostgres postgres() {
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

    // Named apart from the usual startPostgres/stopPostgres, so that a subclass declaring a method
    // of that name does not silently hide these ones.
    @BeforeAll
    static void startEmbeddedPostgres() throws IOException {
        postgres = startServer();
    }

    @AfterAll
    static void stopEmbeddedPostgres() throws IOException {
        if (postgres != null) {
            postgres.close();
            postgres = null;
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }
}
