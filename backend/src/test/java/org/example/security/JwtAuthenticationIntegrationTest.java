package org.example.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.support.TestUsers.authorization;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.example.support.PostgresIntegrationTest;
import org.example.support.TestUsers;
import org.example.user.Role;
import org.example.user.User;
import org.example.user.UserRepository;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Which requests the API accepts with which access token, through the whole application.
 *
 * <p>The rejected tokens are forged by hand (header, claims and HMAC), so that every one differs from
 * a valid token in exactly one point: the algorithm, the key, a claim or the state of the user in
 * the database.
 *
 * <p>The registration is turned on explicitly: its default comes from the environment variable
 * {@code DROSO_AUTH_REGISTRATION_ENABLED}, which a developer may have set to {@code false}.
 */
@SpringBootTest(properties = "droso.auth.registration-enabled=true")
@AutoConfigureMockMvc
class JwtAuthenticationIntegrationTest extends PostgresIntegrationTest {
    /** Any protected endpoint; the defect does not exist, so an accepted token answers 404. */
    private static final String PROTECTED = "/api/v1/defects/1";
    private static final String ME = "/api/v1/auth/me";

    private static final String UNAUTHORIZED =
            "{\"status\":401,\"code\":\"UNAUTHORIZED\",\"message\":\"Unauthorized\",\"fieldErrors\":{}}";
    private static final String FORBIDDEN =
            "{\"status\":403,\"code\":\"FORBIDDEN\",\"message\":\"Forbidden\",\"fieldErrors\":{}}";

    private static final byte[] KEY = Base64.getDecoder().decode(TestUsers.JWT_SECRET);

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

    private User user;

    @BeforeEach
    void storeUser() {
        jdbc.update("DELETE FROM users");
        user = TestUsers.create(users, Role.USER);
    }

    // ---------------------------------------------------------------- forging

    /** The claims of a valid token of the user. */
    private Map<String, Object> validClaims() {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "droso-backend");
        claims.put("sub", String.valueOf(user.getId()));
        claims.put("iat", now);
        claims.put("exp", now + 600);
        claims.put("jti", "test");
        claims.put("email", user.getEmail());
        claims.put("role", user.getRole().name());
        claims.put("ver", 0);
        return claims;
    }

    private String hs256(Map<String, Object> claims) throws Exception {
        return sign("{\"alg\":\"HS256\",\"typ\":\"JWT\"}", claims, "HmacSHA256", KEY);
    }

    private String sign(String header, Map<String, Object> claims, String algorithm, byte[] key) throws Exception {
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        String content = base64.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + base64.encodeToString(jsonMapper.writeValueAsBytes(claims));
        Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(key, algorithm));
        return content + "." + base64.encodeToString(mac.doFinal(content.getBytes(StandardCharsets.US_ASCII)));
    }

    private ResultActions getWith(String token) throws Exception {
        return mockMvc.perform(get(PROTECTED).with(authorization("Bearer " + token)));
    }

    private void assertUnauthorized(ResultActions result, String challenge) throws Exception {
        String body = result
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, challenge))
                .andReturn().getResponse().getContentAsString();
        assertThat(jsonMapper.readTree(body)).isEqualTo(jsonMapper.readTree(UNAUTHORIZED));
    }

    private void assertInvalidToken(String token) throws Exception {
        assertUnauthorized(getWith(token), "Bearer error=\"invalid_token\"");
    }

    // ---------------------------------------------------------------- accepted

    @Test
    @DisplayName("a token issued by the server is accepted")
    void issuedToken() throws Exception {
        mockMvc.perform(get(PROTECTED).with(authorization(TestUsers.bearer(tokens, user))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a token forged with the right key and claims is accepted (the forging itself is sound)")
    void forgedValidToken() throws Exception {
        getWith(hs256(validClaims())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the role comes from the database, not from the claim of the token")
    void roleFromDatabase() throws Exception {
        Map<String, Object> claims = validClaims();
        claims.put("role", "ADMIN");
        String token = hs256(claims);
        jdbc.update("UPDATE users SET role_code = 'MANAGER' WHERE id = ?", user.getId());

        mockMvc.perform(get(ME).with(authorization("Bearer " + token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MANAGER"));
    }

    @Test
    @DisplayName("an authenticated request creates no session and sets no cookie")
    void stateless() throws Exception {
        mockMvc.perform(get(ME).with(authorization(TestUsers.bearer(tokens, user))))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull());
    }

    // ---------------------------------------------------------------- no token

    @Test
    @DisplayName("a request without a token answers 401 UNAUTHORIZED with the challenge Bearer")
    void noToken() throws Exception {
        assertUnauthorized(mockMvc.perform(get(PROTECTED)), "Bearer");
    }

    @Test
    @DisplayName("an unknown URL without a token answers 401, not 404: the API is not mapped for strangers")
    void unknownUrlWithoutToken() throws Exception {
        assertUnauthorized(mockMvc.perform(get("/api/v1/nothing")), "Bearer");
    }

    @Test
    @DisplayName("an unknown URL with a token answers 404 RESOURCE_NOT_FOUND")
    void unknownUrlWithToken() throws Exception {
        mockMvc.perform(get("/api/v1/nothing").with(authorization(TestUsers.bearer(tokens, user))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("a token in the query string is not accepted: it would end up in the logs")
    void tokenInQuery() throws Exception {
        String token = tokens.issue(user).value();

        assertUnauthorized(mockMvc.perform(get(PROTECTED).queryParam("access_token", token)), "Bearer");
    }

    @Test
    @DisplayName("HTTP Basic is not accepted")
    void basicAuthentication() throws Exception {
        String basic = Base64.getEncoder().encodeToString((user.getEmail() + ":" + TestUsers.PASSWORD)
                .getBytes(StandardCharsets.UTF_8));

        assertUnauthorized(mockMvc.perform(get(PROTECTED).with(authorization("Basic " + basic))), "Bearer");
    }

    // ---------------------------------------------------------------- rejected tokens

    @ParameterizedTest
    @ValueSource(strings = {"abc", "a.b.c", "eyJhbGciOiJIUzI1NiJ9.e30."})
    @DisplayName("a token that is not a signed JWT answers 401 with the challenge invalid_token")
    void malformedToken(String token) throws Exception {
        assertInvalidToken(token);
    }

    @Test
    @DisplayName("a token signed with another key answers 401")
    void wrongKey() throws Exception {
        byte[] otherKey = KEY.clone();
        otherKey[0] ^= 1;

        assertInvalidToken(sign("{\"alg\":\"HS256\",\"typ\":\"JWT\"}", validClaims(), "HmacSHA256", otherKey));
    }

    @Test
    @DisplayName("a token whose claims were changed after the signature answers 401")
    void tamperedClaims() throws Exception {
        String[] parts = hs256(validClaims()).split("\\.");
        Map<String, Object> claims = validClaims();
        claims.put("sub", "999999");
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(jsonMapper.writeValueAsBytes(claims));

        assertInvalidToken(parts[0] + "." + payload + "." + parts[2]);
    }

    @Test
    @DisplayName("an unsigned token (alg: none) answers 401")
    void algorithmNone() throws Exception {
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        String token = base64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
                + "." + base64.encodeToString(jsonMapper.writeValueAsBytes(validClaims())) + ".";

        assertInvalidToken(token);
    }

    @Test
    @DisplayName("a token signed with the right key but another algorithm (HS512) answers 401")
    void otherAlgorithm() throws Exception {
        assertInvalidToken(sign("{\"alg\":\"HS512\",\"typ\":\"JWT\"}", validClaims(), "HmacSHA512", KEY));
    }

    @Test
    @DisplayName("an expired token answers 401, beyond the leeway of 60 seconds")
    void expired() throws Exception {
        Map<String, Object> claims = validClaims();
        long now = Instant.now().getEpochSecond();
        claims.put("iat", now - 1900);
        claims.put("exp", now - 100);

        assertInvalidToken(hs256(claims));
    }

    @Test
    @DisplayName("a token that is not valid yet (nbf in the future) answers 401")
    void notYetValid() throws Exception {
        Map<String, Object> claims = validClaims();
        claims.put("nbf", Instant.now().getEpochSecond() + 300);

        assertInvalidToken(hs256(claims));
    }

    @ParameterizedTest
    @ValueSource(strings = {"exp", "sub", "ver"})
    @DisplayName("a token without a required claim answers 401")
    void missingClaim(String claim) throws Exception {
        Map<String, Object> claims = validClaims();
        claims.remove(claim);

        assertInvalidToken(hs256(claims));
    }

    @Test
    @DisplayName("a token of another issuer answers 401")
    void otherIssuer() throws Exception {
        Map<String, Object> claims = validClaims();
        claims.put("iss", "someone-else");

        assertInvalidToken(hs256(claims));
    }

    @ParameterizedTest
    @ValueSource(strings = {"999999", "0", "-1", "01", "abc", "user@example.com", "99999999999999999999"})
    @DisplayName("a subject that is not the id of a user answers 401")
    void unknownSubject(String subject) throws Exception {
        Map<String, Object> claims = validClaims();
        claims.put("sub", subject);

        assertInvalidToken(hs256(claims));
    }

    @Test
    @DisplayName("a token of a deleted user answers 401 at once, although it has not expired")
    void deletedUser() throws Exception {
        String token = tokens.issue(user).value();
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", user.getId());

        assertInvalidToken(token);
    }

    @Test
    @DisplayName("a token of an older version answers 401 at once: incrementing token_version revokes it")
    void revokedToken() throws Exception {
        String token = tokens.issue(user).value();
        jdbc.update("UPDATE users SET token_version = token_version + 1 WHERE id = ?", user.getId());

        assertInvalidToken(token);
    }

    @Test
    @DisplayName("the rejection tells nothing about the reason")
    void noReasonDisclosed() throws Exception {
        Map<String, Object> claims = validClaims();
        claims.put("exp", Instant.now().getEpochSecond() - 100);

        String body = getWith(hs256(claims)).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContainIgnoringCase("expired").doesNotContain("Jwt").doesNotContain("exception");
    }

    // ---------------------------------------------------------------- public and closed endpoints

    @Test
    @DisplayName("the public health contract works without a token")
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("the health probe of Actuator works without a token")
    void actuatorHealthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the metrics of Actuator answer 401 without a token")
    void metricsWithoutToken() throws Exception {
        assertUnauthorized(mockMvc.perform(get("/actuator/metrics")), "Bearer");
    }

    @Test
    @DisplayName("the metrics of Actuator answer 403 FORBIDDEN to any user, an admin included")
    void metricsAreClosed() throws Exception {
        User admin = TestUsers.create(users, Role.ADMIN);

        String body = mockMvc.perform(get("/actuator/metrics").with(authorization(TestUsers.bearer(tokens, admin))))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        assertThat(jsonMapper.readTree(body)).isEqualTo(jsonMapper.readTree(FORBIDDEN));
    }

    @Test
    @DisplayName("the registration works without a token")
    void registrationIsPublic() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"new@example.com\", \"password\": \"" + TestUsers.PASSWORD + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("the login is open for POST only: another method of its URL needs a token")
    void loginIsOpenForPostOnly() throws Exception {
        assertUnauthorized(mockMvc.perform(get("/api/v1/auth/login")), "Bearer");
    }

    @Test
    @DisplayName("a POST of a defect without a token answers 401 and stores nothing")
    void createDefectWithoutToken() throws Exception {
        assertUnauthorized(mockMvc.perform(post("/api/v1/defects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\": \"Crash on start\"}")), "Bearer");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM defects", Integer.class)).isZero();
    }
}
