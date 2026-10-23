package org.example.health;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;

import org.example.health.model.DatabaseHealth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Web layer test without a database: the indicator is replaced by a stub.
 */
class HealthControllerTest {

    private final DatabaseHealthIndicator indicator = mock(DatabaseHealthIndicator.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        HealthService service = new HealthService(indicator, Clock.systemUTC());
        mockMvc = MockMvcBuilders.standaloneSetup(new HealthController(service)).build();
    }

    @Test
    @DisplayName("answers 200 OK when the database is reachable")
    void returns200WhenUp() throws Exception {
        when(indicator.check()).thenReturn(DatabaseHealth.up(2));

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database.status").value("UP"))
                .andExpect(jsonPath("$.database.latencyMs").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("answers 503 Service Unavailable when the database is not reachable")
    void returns503WhenDown() throws Exception {
        when(indicator.check()).thenReturn(DatabaseHealth.down(2000));

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.database.status").value("DOWN"));
    }

    @Test
    @DisplayName("the body contains no technical details in the failing case")
    void bodyContainsNoDetails() throws Exception {
        when(indicator.check()).thenReturn(DatabaseHealth.down(2000));

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    @DisplayName("the JSON body contains exactly the documented fields")
    void bodyMatchesTheContract() throws Exception {
        when(indicator.check()).thenReturn(DatabaseHealth.up(7));

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database.status").value("UP"))
                .andExpect(jsonPath("$.database.latencyMs").value(7))
                // No derived helper fields may leak into the public contract.
                .andExpect(jsonPath("$.database.up").doesNotExist())
                .andExpect(jsonPath("$.database.down").doesNotExist())
                .andExpect(jsonPath("$.database.isUp").doesNotExist())
                .andExpect(jsonPath("$.*", org.hamcrest.Matchers.hasSize(3)));
    }
}