package org.example.web.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.example.health.HealthController;
import org.example.health.HealthService;
import org.example.health.model.DatabaseHealth;
import org.example.health.model.HealthResponse;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The error handling within the real Spring MVC configuration of the application, without a
 * database: the health service is replaced by a mock.
 */
@WebMvcTest(controllers = HealthController.class)
// Without the filters of Spring Security: this test is about the error handling of Spring MVC, and
// the slice would apply the default security of Spring Boot, not SecurityConfig. The security of
// the API is tested against the whole application (JwtAuthenticationIntegrationTest).
@AutoConfigureMockMvc(addFilters = false)
class ApiErrorWebIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HealthService healthService;

    @Test
    @DisplayName("an unknown URL answers 404 RESOURCE_NOT_FOUND")
    void unknownUrl() throws Exception {
        mockMvc.perform(get("/api/v1/no-such-resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @Test
    @DisplayName("an unsupported method answers 405 METHOD_NOT_ALLOWED with the Allow header")
    void unsupportedMethod() throws Exception {
        mockMvc.perform(post("/api/v1/health"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", Matchers.containsString("GET")))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("the health contract is unchanged: 200 while the database is up")
    void healthUp() throws Exception {
        when(healthService.check()).thenReturn(HealthResponse.of(DatabaseHealth.up(3), Instant.now()));

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database.status").value("UP"))
                .andExpect(jsonPath("$.*", Matchers.hasSize(3)));
    }

    @Test
    @DisplayName("the health contract is unchanged: 503 while the database is down")
    void healthDown() throws Exception {
        when(healthService.check()).thenReturn(HealthResponse.of(DatabaseHealth.down(2000), Instant.now()));

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.database.status").value("DOWN"))
                .andExpect(jsonPath("$.*", Matchers.hasSize(3)));
    }

    @Test
    @DisplayName("an unexpected failure answers 500 INTERNAL_ERROR without any technical detail")
    void unexpectedFailure() throws Exception {
        when(healthService.check()).thenThrow(new IllegalStateException(
                "Connection to jdbc:postgresql://10.0.0.5:5432/secret_db failed for user admin"));

        String body = mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Internal server error"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.*", Matchers.hasSize(4)))
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("jdbc:")
                .doesNotContain("10.0.0.5")
                .doesNotContain("secret_db")
                .doesNotContain("admin")
                .doesNotContain("IllegalStateException")
                .doesNotContain("org.example");
    }
}
