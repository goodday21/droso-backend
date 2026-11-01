package org.example.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.support.TestUsers.authorization;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.example.security.AccessTokenService;
import org.example.support.PostgresIntegrationTest;
import org.example.support.TestUsers;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/v1/users}, {@code PUT /api/v1/users/{id}/role} and {@code DELETE /api/v1/users/{id}}
 * through the whole application. One case per kind of error; the branches of the rule of the last
 * administrator are pinned by {@code UserManagementServiceTest}, its locks by
 * {@code UserManagementLockingIntegrationTest}.
 *
 * <p>The registration is turned on explicitly (as in the other tests that register), since a
 * deleted email is registered again here.
 */
@SpringBootTest(properties = "droso.auth.registration-enabled=true")
@AutoConfigureMockMvc
class UserManagementIntegrationTest extends PostgresIntegrationTest {

    private static final String URL = "/api/v1/users";
    private static final String FORBIDDEN =
            "{\"status\":403,\"code\":\"FORBIDDEN\",\"message\":\"Forbidden\",\"fieldErrors\":{}}";
    private static final String NOT_FOUND =
            "{\"status\":404,\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"Resource not found\",\"fieldErrors\":{}}";
    private static final String LAST_ADMIN =
            "{\"status\":409,\"code\":\"LAST_ADMIN\",\"message\":\"The last administrator cannot be removed\",\"fieldErrors\":{}}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private UserRepository users;

    @Autowired
    private AccessTokenService tokens;

    /** The only administrator, who sends the requests. */
    private User admin;
    private String bearer;

    @BeforeEach
    void logIn() {
        admin = TestUsers.create(users, "admin@example.com", Role.ADMIN);
        bearer = TestUsers.bearer(tokens, admin);
    }

    private ResultActions changeRole(long id, String json) throws Exception {
        return mockMvc.perform(put(URL + "/{id}/role", id).with(authorization(bearer))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private void assertBody(ResultActions result, String expected) throws Exception {
        assertThat(jsonMapper.readTree(result.andReturn().getResponse().getContentAsString()))
                .isEqualTo(jsonMapper.readTree(expected));
    }

    private String roleOf(long id) {
        return jdbc.queryForObject("SELECT role_code FROM users WHERE id = ?", String.class, id);
    }

    // ---------------------------------------------------------------- access

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"USER", "MANAGER"})
    @DisplayName("a user without USER_MANAGE gets 403 on every endpoint, and nothing changes")
    void forbiddenWithoutUserManage(Role role) throws Exception {
        String other = TestUsers.bearer(tokens, TestUsers.create(users, role));

        assertBody(mockMvc.perform(get(URL).with(authorization(other))).andExpect(status().isForbidden()), FORBIDDEN);
        assertBody(mockMvc.perform(put(URL + "/{id}/role", admin.getId()).with(authorization(other))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\": \"USER\"}"))
                .andExpect(status().isForbidden()), FORBIDDEN);
        assertBody(mockMvc.perform(delete(URL + "/{id}", admin.getId()).with(authorization(other)))
                .andExpect(status().isForbidden()), FORBIDDEN);
        assertThat(roleOf(admin.getId())).isEqualTo("ADMIN");
        assertThat(users.findById(admin.getId()).orElseThrow().isActive()).isTrue();
    }

    // ---------------------------------------------------------------- list

    @Test
    @DisplayName("by default the list answers a page of the active users, newest first, without the deleted ones")
    void list() throws Exception {
        User first = TestUsers.create(users, Role.USER);
        User deleted = TestUsers.create(users, Role.MANAGER);
        User last = TestUsers.create(users, Role.MANAGER);
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", deleted.getId());

        mockMvc.perform(get(URL).with(authorization(bearer)).param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items[*].id", Matchers.contains(last.getId().intValue(), first.getId().intValue())))
                .andExpect(jsonPath("$.items[0].email").value(last.getEmail()))
                .andExpect(jsonPath("$.items[0].role").value("MANAGER"))
                .andExpect(jsonPath("$.items[0].createdAt").isString())
                .andExpect(jsonPath("$.items[0].*", Matchers.hasSize(4)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.*", Matchers.hasSize(5)));

        mockMvc.perform(get(URL).with(authorization(bearer)).param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", Matchers.contains(admin.getId().intValue())));
    }

    @Test
    @DisplayName("sorted by email, the list follows the bytes, punctuation included, whatever the locale of the database")
    void listByEmail() throws Exception {
        // Under en_US or ru_RU the punctuation would count last: a.b, ab, a-c, admin.
        TestUsers.create(users, "ab@example.com", Role.USER);
        TestUsers.create(users, "a.b@example.com", Role.USER);
        TestUsers.create(users, "a-c@example.com", Role.USER);

        mockMvc.perform(get(URL).with(authorization(bearer)).param("sort", "email").param("direction", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].email", Matchers.contains(
                        "a-c@example.com", "a.b@example.com", "ab@example.com", "admin@example.com")));
        mockMvc.perform(get(URL).with(authorization(bearer)).param("sort", "email"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].email", Matchers.contains(
                        "admin@example.com", "ab@example.com", "a.b@example.com", "a-c@example.com")));
    }

    @Test
    @DisplayName("sorted by role, the list orders the codes alphabetically and equal roles by id in the same direction")
    void listByRole() throws Exception {
        User user1 = TestUsers.create(users, Role.USER);
        User manager = TestUsers.create(users, Role.MANAGER);
        User user2 = TestUsers.create(users, Role.USER);

        mockMvc.perform(get(URL).with(authorization(bearer)).param("sort", "role").param("direction", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", Matchers.contains(admin.getId().intValue(),
                        manager.getId().intValue(), user1.getId().intValue(), user2.getId().intValue())));
        mockMvc.perform(get(URL).with(authorization(bearer)).param("sort", "role").param("direction", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", Matchers.contains(user2.getId().intValue(),
                        user1.getId().intValue(), manager.getId().intValue(), admin.getId().intValue())));
    }

    @Test
    @DisplayName("a field or a direction of the order not spelled in lower case answers 400 VALIDATION_ERROR")
    void listInvalidOrder() throws Exception {
        assertBody(mockMvc.perform(get(URL).with(authorization(bearer)).param("sort", "Email"))
                        .andExpect(status().isBadRequest()),
                "{\"status\":400,\"code\":\"VALIDATION_ERROR\",\"message\":\"Request validation failed\","
                        + "\"fieldErrors\":{\"sort\":\"has an invalid value\"}}");
        assertBody(mockMvc.perform(get(URL).with(authorization(bearer)).param("direction", "ASC"))
                        .andExpect(status().isBadRequest()),
                "{\"status\":400,\"code\":\"VALIDATION_ERROR\",\"message\":\"Request validation failed\","
                        + "\"fieldErrors\":{\"direction\":\"has an invalid value\"}}");
    }

    @Test
    @DisplayName("without parameters the list answers the first page of 20")
    void listDefaults() throws Exception {
        mockMvc.perform(get(URL).with(authorization(bearer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("a size above 100 answers 400 VALIDATION_ERROR instead of being cut")
    void listTooLargePage() throws Exception {
        assertBody(mockMvc.perform(get(URL).with(authorization(bearer)).param("size", "101"))
                        .andExpect(status().isBadRequest()),
                "{\"status\":400,\"code\":\"VALIDATION_ERROR\",\"message\":\"Request validation failed\","
                        + "\"fieldErrors\":{\"size\":\"must be less than or equal to 100\"}}");
    }

    // ---------------------------------------------------------------- role

    @Test
    @DisplayName("a role change answers 200 with the user, applies at once and keeps the tokens valid")
    void changeRole() throws Exception {
        User user = TestUsers.create(users, Role.USER);
        String userBearer = TestUsers.bearer(tokens, user);

        changeRole(user.getId(), "{\"role\": \"MANAGER\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId()))
                .andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.*", Matchers.hasSize(4)));

        assertThat(roleOf(user.getId())).isEqualTo("MANAGER");
        // The old token still works, with the permissions of the new role.
        mockMvc.perform(get("/api/v1/auth/me").with(authorization(userBearer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", Matchers.hasItem("DEFECT_UPDATE")));
    }

    @Test
    @DisplayName("a missing or unknown role answers 400 VALIDATION_ERROR and changes nothing")
    void invalidRole() throws Exception {
        User user = TestUsers.create(users, Role.USER);

        assertBody(changeRole(user.getId(), "{}").andExpect(status().isBadRequest()),
                "{\"status\":400,\"code\":\"VALIDATION_ERROR\",\"message\":\"Request validation failed\","
                        + "\"fieldErrors\":{\"role\":\"must not be null\"}}");
        changeRole(user.getId(), "{\"role\": \"ROOT\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.role").value("has an invalid value"));
        assertThat(roleOf(user.getId())).isEqualTo("USER");
    }

    @Test
    @DisplayName("the role of a missing or deleted user answers 404")
    void changeRoleNotFound() throws Exception {
        User deleted = TestUsers.create(users, Role.USER);
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", deleted.getId());

        assertBody(changeRole(999_999, "{\"role\": \"USER\"}").andExpect(status().isNotFound()), NOT_FOUND);
        assertBody(changeRole(deleted.getId(), "{\"role\": \"ADMIN\"}").andExpect(status().isNotFound()), NOT_FOUND);
        assertThat(roleOf(deleted.getId())).isEqualTo("USER");
    }

    @Test
    @DisplayName("the last administrator cannot demote themselves: 409 LAST_ADMIN; with a second one they can")
    void demoteLastAdmin() throws Exception {
        assertBody(changeRole(admin.getId(), "{\"role\": \"MANAGER\"}").andExpect(status().isConflict()), LAST_ADMIN);
        assertThat(roleOf(admin.getId())).isEqualTo("ADMIN");

        TestUsers.create(users, Role.ADMIN);
        changeRole(admin.getId(), "{\"role\": \"MANAGER\"}").andExpect(status().isOk());
        // The token is still valid, but no longer an administrator's.
        mockMvc.perform(get(URL).with(authorization(bearer))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a non-canonical id answers 400 VALIDATION_ERROR")
    void nonCanonicalId() throws Exception {
        mockMvc.perform(put(URL + "/0x1/role").with(authorization(bearer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\": \"USER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.id").value("has an invalid value"));
    }

    // ---------------------------------------------------------------- deletion

    @Test
    @DisplayName("a deletion answers 204, revokes the tokens, ends the login and frees the email")
    void deleteUser() throws Exception {
        User user = TestUsers.create(users, "victim@example.com", Role.MANAGER);
        String userBearer = TestUsers.bearer(tokens, user);

        mockMvc.perform(delete(URL + "/{id}", user.getId()).with(authorization(bearer)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        Map<String, Object> row = jdbc.queryForMap("SELECT deleted_at, token_version FROM users WHERE id = ?", user.getId());
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(row.get("token_version")).isEqualTo(1);
        mockMvc.perform(get("/api/v1/auth/me").with(authorization(userBearer))).andExpect(status().isUnauthorized());
        String credentials = "{\"email\": \"victim@example.com\", \"password\": \"" + TestUsers.PASSWORD + "\"}";
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(Matchers.not(user.getId().intValue())));
    }

    @Test
    @DisplayName("deleting a missing or already deleted user answers 404")
    void deleteNotFound() throws Exception {
        User user = TestUsers.create(users, Role.USER);
        mockMvc.perform(delete(URL + "/{id}", user.getId()).with(authorization(bearer))).andExpect(status().isNoContent());

        assertBody(mockMvc.perform(delete(URL + "/{id}", user.getId()).with(authorization(bearer)))
                .andExpect(status().isNotFound()), NOT_FOUND);
        assertBody(mockMvc.perform(delete(URL + "/{id}", 999_999).with(authorization(bearer)))
                .andExpect(status().isNotFound()), NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT token_version FROM users WHERE id = ?", Integer.class, user.getId()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the last administrator cannot delete themselves: 409 LAST_ADMIN, the token stays valid")
    void deleteLastAdmin() throws Exception {
        assertBody(mockMvc.perform(delete(URL + "/{id}", admin.getId()).with(authorization(bearer)))
                .andExpect(status().isConflict()), LAST_ADMIN);

        mockMvc.perform(get(URL).with(authorization(bearer))).andExpect(status().isOk());
    }
}
