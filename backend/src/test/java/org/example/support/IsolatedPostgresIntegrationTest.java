package org.example.support;

import java.io.IOException;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base of the Spring tests that stop the database to check how the application behaves without
 * it: every test class gets its own PostgreSQL 14 server, started before the class and stopped
 * after it, and its own application context. Everything else extends the faster
 * {@link PostgresIntegrationTest}, whose server is shared.
 *
 * <p>A test may stop the server ({@code postgres().close()}); it is then stopped for the rest of
 * the class. The tables are not emptied between the tests: the server lives for one class only.
 *
 * <p>{@code @DirtiesContext} is required, not a precaution: the {@code @DynamicPropertySource}
 * method is the same for every subclass, so Spring's context cache would hand the next test class
 * with the same configuration the context of the previous one, whose datasource points to a
 * server that has already been stopped. Closing the context after the class keeps one server and
 * one context per class.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class IsolatedPostgresIntegrationTest {

    private static EmbeddedPostgres postgres;

    /** The server of the running test class. */
    protected static EmbeddedPostgres postgres() {
        return postgres;
    }

    // Named apart from the usual startPostgres/stopPostgres, so that a subclass declaring a method
    // of that name does not silently hide these ones.
    @BeforeAll
    static void startIsolatedPostgres() throws IOException {
        postgres = PostgresIntegrationTest.startServer();
    }

    @AfterAll
    static void stopIsolatedPostgres() throws IOException {
        if (postgres != null) {
            postgres.close();
            postgres = null;
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        PostgresIntegrationTest.registerDatasource(registry, IsolatedPostgresIntegrationTest::postgres);
    }
}
