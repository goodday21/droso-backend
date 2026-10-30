package org.example.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import org.example.bootstrap.BootstrapAdminService.Outcome;
import org.example.support.PostgresIntegrationTest;
import org.example.support.TestUsers;
import org.example.user.Role;
import org.example.user.User;
import org.example.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link BootstrapAdminService} against the real database and the real password encoder.
 *
 * <p>The service is called directly with the settings of each test rather than through a start-up
 * with other properties: every distinct configuration would start another application context. The
 * start-up of the tests themselves creates nothing ({@code PostgresIntegrationTest} clears the
 * settings), and the branches of the settings are covered by {@code BootstrapAdminServiceTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BootstrapAdminIntegrationTest extends PostgresIntegrationTest {

    private static final String EMAIL = "admin@example.com";
    private static final String PASSWORD = "Adm1n-Secret!";

    @Autowired
    private BootstrapAdminService service;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mockMvc;

    private Outcome bootstrap(String email) {
        return service.bootstrap(new BootstrapAdminProperties(email, PASSWORD));
    }

    private List<Map<String, Object>> admins() {
        return jdbc.queryForList("SELECT email, password_hash, deleted_at FROM users WHERE role_code = 'ADMIN'");
    }

    @Test
    @DisplayName("the created administrator is stored normalized with a BCrypt hash, logs in and may manage the users")
    void createdAdminLogsIn() throws Exception {
        assertThat(bootstrap(" Admin@Example.COM ")).isEqualTo(Outcome.CREATED);

        Map<String, Object> admin = admins().getFirst();
        assertThat(admin.get("email")).isEqualTo(EMAIL);
        assertThat((String) admin.get("password_hash")).startsWith("{bcrypt}$2a$12$").doesNotContain(PASSWORD);

        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + EMAIL + "\", \"password\": \"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(get("/api/v1/auth/me")
                        .with(TestUsers.authorization("Bearer " + JsonPath.read(body, "$.accessToken"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.permissions[3]").value("USER_MANAGE"));
    }

    @Test
    @DisplayName("a second start-up keeps the one administrator, even with another email configured")
    void secondStartUp() {
        bootstrap(EMAIL);

        assertThat(bootstrap("other.admin@example.com")).isEqualTo(Outcome.ADMIN_EXISTS);
        assertThat(admins()).hasSize(1);
    }

    @Test
    @DisplayName("an email of an active user, in another case, stops the start-up and leaves that user a USER")
    void emailOfActiveUser() {
        User user = TestUsers.create(users, EMAIL, Role.USER);

        assertThatThrownBy(() -> bootstrap("ADMIN@example.com")).isInstanceOf(BootstrapAdminException.class);
        assertThat(admins()).isEmpty();
        assertThat(users.findById(user.getId()).orElseThrow().getRole()).isEqualTo(Role.USER);
    }

    @Test
    @DisplayName("a deleted administrator does not count, and the email of a deleted user may be used")
    void deletedUsersDoNotCount() {
        User deletedAdmin = TestUsers.create(users, EMAIL, Role.ADMIN);
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", deletedAdmin.getId());

        assertThat(bootstrap(EMAIL)).isEqualTo(Outcome.CREATED);
        assertThat(admins()).hasSize(2).filteredOn(admin -> admin.get("deleted_at") == null).hasSize(1);
    }
}
