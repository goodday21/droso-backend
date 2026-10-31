package org.example.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.support.TestUsers.authorization;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import org.example.support.PostgresIntegrationTest;
import org.example.support.TestUsers;
import org.example.user.Role;
import org.example.user.User;
import org.example.user.UserRepository;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /api/v1/auth/login} and {@code GET /api/v1/auth/me} through the whole application
 * against a real PostgreSQL server.
 *
 * <p>The registration is turned on explicitly: its default comes from the environment variable
 * {@code DROSO_AUTH_REGISTRATION_ENABLED}, which a developer may have set to {@code false}.
 */
@SpringBootTest(properties = "droso.auth.registration-enabled=true")
@AutoConfigureMockMvc
class LoginIntegrationTest extends PostgresIntegrationTest {
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String ME = "/api/v1/auth/me";
    private static final String EMAIL = "qwe.qwe+1@gmail.com";

    private static final String INVALID_CREDENTIALS = "{\"status\":401,\"code\":\"INVALID_CREDENTIALS\","
            + "\"message\":\"Invalid email or password\",\"fieldErrors\":{}}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private UserRepository users;

    private User user;

    @BeforeEach
    void storeUser() {
        user = TestUsers.create(users, EMAIL, Role.MANAGER);
    }

    private ResultActions login(Object email, Object password) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        return mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)));
    }

    private String accessToken(ResultActions login) throws Exception {
        return JsonPath.read(login.andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    /** The claims of a token, read without checking it: what any holder of the token can see. */
    private JsonNode claims(String token) {
        String payload = token.split("\\.")[1];
        return jsonMapper.readTree(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
    }

    private void assertInvalidCredentials(ResultActions result) throws Exception {
        String body = result
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andReturn().getResponse().getContentAsString();
        assertThat(jsonMapper.readTree(body)).isEqualTo(jsonMapper.readTree(INVALID_CREDENTIALS));
    }

    // ---------------------------------------------------------------- successful login

    @Test
    @DisplayName("a valid login answers 200 with a bearer token of 15 minutes that no cache may keep, and the refresh cookie")
    void login() throws Exception {
        ResultActions result = login(EMAIL, TestUsers.PASSWORD)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, Matchers.matchesPattern(
                        "droso_refresh=[A-Za-z0-9_-]{43}; Path=/api/v1/auth; Max-Age=604800; Expires=.*; "
                                + "Secure; HttpOnly; SameSite=Strict")))
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.*", Matchers.hasSize(3)));

        assertThat(result.andReturn().getResponse().getContentAsString()).doesNotContain(TestUsers.PASSWORD);
    }

    @Test
    @DisplayName("the token carries the agreed claims, the user id as subject, and nothing secret")
    void claims() throws Exception {
        String token = accessToken(login(EMAIL, TestUsers.PASSWORD));

        String header = new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8);
        // Nimbus adds "kid", the SHA-256 thumbprint of the key (RFC 7638): it does not reveal the key.
        JsonNode jose = jsonMapper.readTree(header);
        assertThat(jose.propertyNames()).containsExactlyInAnyOrder("alg", "typ", "kid");
        assertThat(jose.get("alg").asString()).isEqualTo("HS256");
        assertThat(jose.get("typ").asString()).isEqualTo("JWT");
        assertThat(header).doesNotContain(TestUsers.JWT_SECRET);

        JsonNode claims = claims(token);
        assertThat(claims.propertyNames()).containsExactlyInAnyOrder(
                "iss", "sub", "iat", "exp", "jti", "email", "role", "ver");
        assertThat(claims.get("iss").asString()).isEqualTo("droso-backend");
        assertThat(claims.get("sub").asString()).isEqualTo(String.valueOf(user.getId()));
        assertThat(claims.get("email").asString()).isEqualTo(EMAIL);
        assertThat(claims.get("role").asString()).isEqualTo("MANAGER");
        assertThat(claims.get("ver").asInt()).isZero();
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(900);
        assertThat(claims.toString()).doesNotContain(TestUsers.PASSWORD).doesNotContain("bcrypt");
    }

    @Test
    @DisplayName("every token has its own id (jti)")
    void uniqueTokenIds() throws Exception {
        JsonNode first = claims(accessToken(login(EMAIL, TestUsers.PASSWORD)));
        JsonNode second = claims(accessToken(login(EMAIL, TestUsers.PASSWORD)));

        assertThat(first.get("jti").asString()).isNotEqualTo(second.get("jti").asString());
    }

    @Test
    @DisplayName("the email is normalized like on the registration: case and surrounding spaces do not matter")
    void emailIsNormalized() throws Exception {
        login(" QWE.Qwe+1@GMail.COM ", TestUsers.PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a registered user logs in and /me answers the user (the full path, BCrypt cost 12)")
    void registerThenLogin() throws Exception {
        String password = "Other-Pa55word";
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"new.user@example.com\", \"password\": \"" + password + "\"}"))
                .andExpect(status().isCreated());

        String token = accessToken(login("new.user@example.com", password).andExpect(status().isOk()));

        mockMvc.perform(get(ME).with(authorization("Bearer " + token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("new.user@example.com"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    @DisplayName("/me answers the user of the token: id, email, role, its permissions and creation time, nothing else")
    void me() throws Exception {
        String token = accessToken(login(EMAIL, TestUsers.PASSWORD));

        mockMvc.perform(get(ME).with(authorization("Bearer " + token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId()))
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.permissions", Matchers.contains("DEFECT_READ", "DEFECT_CREATE", "DEFECT_UPDATE")))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.*", Matchers.hasSize(5)));
    }

    // ---------------------------------------------------------------- failed login

    @Test
    @DisplayName("a wrong password answers 401 INVALID_CREDENTIALS")
    void wrongPassword() throws Exception {
        assertInvalidCredentials(login(EMAIL, "Wrong-Pa55word"));
    }

    @Test
    @DisplayName("an unknown email answers exactly like a wrong password")
    void unknownEmail() throws Exception {
        assertInvalidCredentials(login("nobody@gmail.com", TestUsers.PASSWORD));
    }

    @Test
    @DisplayName("a deleted user answers exactly like a wrong password, even with the right one")
    void deletedUser() throws Exception {
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", user.getId());

        assertInvalidCredentials(login(EMAIL, TestUsers.PASSWORD));
    }

    @ParameterizedTest
    @ValueSource(strings = {" Sup3r-Secret!", "Sup3r-Secret! ", "sup3r-secret!", "SUP3R-SECRET!"})
    @DisplayName("the password is compared exactly: neither stripped nor case-insensitive")
    void passwordIsExact(String password) throws Exception {
        assertInvalidCredentials(login(EMAIL, password));
    }

    @Test
    @DisplayName("a password the current policy would reject is still checked, not refused with 400")
    void passwordPolicyIsNotApplied() throws Exception {
        assertInvalidCredentials(login(EMAIL, "short"));
    }

    // ---------------------------------------------------------------- invalid requests

    @Test
    @DisplayName("a missing email and password answer 400 VALIDATION_ERROR for both fields")
    void missingFields() throws Exception {
        login(null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.email").value("must not be blank"))
                .andExpect(jsonPath("$.fieldErrors.password").value("must not be null"));
    }

    @Test
    @DisplayName("an oversized password answers 400 before it reaches the password hash")
    void oversizedPassword() throws Exception {
        login(EMAIL, "A1-" + "a".repeat(62))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.password").value("length must be between 0 and 64"));
    }

    @Test
    @DisplayName("an oversized email answers 400")
    void oversizedEmail() throws Exception {
        login("a".repeat(250) + "@x.io", TestUsers.PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").value("length must be between 0 and 254"));
    }

    @Test
    @DisplayName("an unknown property answers 400 instead of being ignored")
    void unknownProperty() throws Exception {
        mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(
                        "{\"email\": \"" + EMAIL + "\", \"password\": \"" + TestUsers.PASSWORD + "\", \"role\": \"ADMIN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.role").value("is not allowed"));
    }

    @Test
    @DisplayName("/me without a token answers 401")
    void meWithoutToken() throws Exception {
        mockMvc.perform(get(ME)).andExpect(status().isUnauthorized());
    }
}
