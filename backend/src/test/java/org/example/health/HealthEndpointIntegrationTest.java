package org.example.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end test of {@code GET /api/v1/health} against a real PostgreSQL server.
 *
 * <p>The database is a real PostgreSQL 14 instance started in-process (zonky embedded-postgres),
 * therefore no Docker daemon is required. Both outcomes of the endpoint are verified: 200 while
 * the server is running and 503 after the server has been shut down.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HealthEndpointIntegrationTest {

    private static EmbeddedPostgres postgres;

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder()
                .setPort(0)
                .setRegisterShutdownHook(true)
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
    @Order(1)
    @DisplayName("answers 200 OK while PostgreSQL is running")
    void returnsOkWhenDatabaseIsUp() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database.status").value("UP"))
                .andExpect(jsonPath("$.database.latencyMs").isNumber())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @Order(2)
    @DisplayName("answers 503 Service Unavailable after PostgreSQL has been stopped")
    void returnsServiceUnavailableWhenDatabaseIsDown() throws Exception {
        postgres.close();

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.database.status").value("DOWN"));
    }

    @Test
    @Order(3)
    @DisplayName("the failing response leaks no technical details")
    void failingResponseLeaksNoDetails() throws Exception {
        String body = mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("jdbc:")
                .doesNotContain("org.postgresql")
                .doesNotContain("Exception")
                .doesNotContain("localhost")
                .doesNotContain("password")
                .doesNotContain("stackTrace");
    }
}