package org.example;

import java.time.Clock;
import java.util.Locale;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/**
 * Entry point of the application.
 *
 * <p>The {@code health} package contains the {@code GET /api/v1/health} endpoint that verifies
 * real PostgreSQL availability and answers 200 (UP) or 503 (DOWN).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {

    public static void main(String[] args) {
        // The JDBC driver localizes its own messages with the locale of the JVM. On a Russian host
        // it would append a Russian "Position: 15" to an otherwise English server message, which
        // a non-UTF-8 console renders as mojibake. English is therefore forced here, which also
        // covers the runs started from the IDE, where the Gradle settings do not apply.
        Locale.setDefault(Locale.ENGLISH);

        SpringApplication.run(Application.class, args);
    }

    /**
     * The clock is injected rather than read statically, so that the tests can freeze the time.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}