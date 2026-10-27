package org.example.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.example.support.PostgresIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code POST /api/v1/auth/register} while the registration is turned off. A class of its own,
 * because the property needs an application context of its own.
 */
@SpringBootTest(properties = "droso.auth.registration-enabled=false")
@AutoConfigureMockMvc
class RegistrationDisabledIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("a valid registration answers 403 REGISTRATION_DISABLED and stores nothing")
    void registrationDisabled() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"qwe@gmail.com\", \"password\": \"Sup3r-Secret!\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("REGISTRATION_DISABLED"))
                .andExpect(jsonPath("$.message").value("Registration is disabled"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.*", Matchers.hasSize(4)));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
    }
}
