package org.example.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import org.example.support.PostgresIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /api/v1/auth/register} through the whole application against a real PostgreSQL
 * server whose schema was created by the Flyway migrations.
 *
 * <p>What was stored is read back with plain JDBC, independently of JPA and of the response. Every
 * rejected request is checked to leave the table empty.
 *
 * <p>The registration is turned on explicitly: its default comes from the environment variable
 * {@code DROSO_AUTH_REGISTRATION_ENABLED}, which a developer may have set to {@code false}.
 */
@SpringBootTest(properties = "droso.auth.registration-enabled=true")
@AutoConfigureMockMvc
class RegistrationIntegrationTest extends PostgresIntegrationTest {
    private static final String URL = "/api/v1/auth/register";
    private static final String PASSWORD = "Sup3r-Secret!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private ResultActions register(Object email, Object password) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        return postJson(jsonMapper.writeValueAsString(body));
    }

    private ResultActions postJson(String json) throws Exception {
        return mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private int userCount() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("a valid registration answers 201 with the user and stores a hash of the password")
    void register() throws Exception {
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        String body = register(" Qwe.Qwe+1@GMail.com ", PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.email").value("qwe.qwe+1@gmail.com"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.*", Matchers.hasSize(4)))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(PASSWORD).doesNotContain("bcrypt").doesNotContain("password");

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM users");
        assertThat(((Number) row.get("id")).longValue()).isEqualTo(((Number) JsonPath.read(body, "$.id")).longValue());
        assertThat(row.get("email")).isEqualTo("qwe.qwe+1@gmail.com");
        assertThat(row.get("role_code")).isEqualTo("USER");
        assertThat(row.get("deleted_at")).isNull();

        String hash = (String) row.get("password_hash");
        assertThat(hash).startsWith("{bcrypt}$2a$12$").doesNotContain(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();

        Instant createdAt = ((Timestamp) row.get("created_at")).toInstant();
        assertThat(createdAt).isEqualTo(((Timestamp) row.get("updated_at")).toInstant());
        assertThat(createdAt).isBetween(before, Instant.now());
        assertThat(Instant.parse(JsonPath.read(body, "$.createdAt"))).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("an email of an active user answers 409, whatever its case and surrounding spaces")
    void duplicateEmail() throws Exception {
        register("qwe@gmail.com", PASSWORD).andExpect(status().isCreated());

        register(" QWE@Gmail.COM ", "Other-Pa55word")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"))
                .andExpect(jsonPath("$.message").value("Email is already registered"))
                .andExpect(jsonPath("$.fieldErrors.email").value("is already registered"))
                .andExpect(jsonPath("$.fieldErrors.*", Matchers.hasSize(1)));

        assertThat(userCount()).isEqualTo(1);
        // The first registration is left as it was.
        String hash = jdbc.queryForObject("SELECT password_hash FROM users", String.class);
        assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();
    }

    @Test
    @DisplayName("the part after a plus makes another account")
    void plusAlias() throws Exception {
        register("qwe@gmail.com", PASSWORD).andExpect(status().isCreated());
        register("qwe+1@gmail.com", PASSWORD).andExpect(status().isCreated());

        assertThat(jdbc.queryForList("SELECT email FROM users ORDER BY id", String.class))
                .containsExactly("qwe@gmail.com", "qwe+1@gmail.com");
    }

    @Test
    @DisplayName("the email of a deleted user may register again, as a new user")
    void reRegisterAfterDeletion() throws Exception {
        register("qwe@gmail.com", PASSWORD).andExpect(status().isCreated());
        jdbc.update("UPDATE users SET deleted_at = now()");

        register("qwe@gmail.com", "Other-Pa55word").andExpect(status().isCreated());

        assertThat(jdbc.queryForList("SELECT deleted_at IS NULL FROM users ORDER BY id", Boolean.class))
                .containsExactly(false, true);
        // And the new active user blocks the address again.
        register("qwe@gmail.com", PASSWORD).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a client cannot choose the role")
    void roleIsNotAccepted() throws Exception {
        postJson("{\"email\": \"qwe@gmail.com\", \"password\": \"" + PASSWORD + "\", \"role\": \"ADMIN\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.role").value("is not allowed"));

        assertThat(userCount()).isZero();
    }

    @Test
    @DisplayName("a blank email is reported once")
    void blankEmail() throws Exception {
        register(" \u200B", PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").value("must not be blank"))
                .andExpect(jsonPath("$.fieldErrors.*", Matchers.hasSize(1)));

        assertThat(userCount()).isZero();
    }

    @Test
    @DisplayName("a malformed email answers 400 for the field")
    void malformedEmail() throws Exception {
        register("qwe..qwe@gmail.com", PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.email").value("must be a well-formed email address"))
                .andExpect(jsonPath("$.fieldErrors.*", Matchers.hasSize(1)));

        assertThat(userCount()).isZero();
    }

    @Test
    @DisplayName("an email longer than 254 characters answers 400 for the field")
    void tooLongEmail() throws Exception {
        register("x".repeat(64) + "@" + "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + ".com",
                PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").value("must be a well-formed email address"));

        assertThat(userCount()).isZero();
    }

    @Test
    @DisplayName("every violated password rule is reported, in a fixed order and without the password")
    void weakPassword() throws Exception {
        String body = register("qwe@gmail.com", "secret")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.password").value(
                        "must be 8 to 64 characters long; must contain a digit; "
                                + "must contain a punctuation character; must contain an upper case letter"))
                .andExpect(jsonPath("$.fieldErrors.*", Matchers.hasSize(1)))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("secret");
        assertThat(userCount()).isZero();
    }

    @Test
    @DisplayName("a password with a character other than printable ASCII answers 400")
    void passwordCharacters() throws Exception {
        register("qwe@gmail.com", "Sup3r-Секрет!")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password")
                        .value("must contain only Latin letters, digits and punctuation characters"));

        assertThat(userCount()).isZero();
    }

    @Test
    @DisplayName("a password is not stripped: surrounding spaces are rejected, not removed")
    void passwordIsNotStripped() throws Exception {
        register("qwe@gmail.com", " " + PASSWORD + " ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password")
                        .value("must contain only Latin letters, digits and punctuation characters"));

        assertThat(userCount()).isZero();
    }

    @Test
    @DisplayName("the longest valid password is accepted, a longer one answers 400")
    void passwordLength() throws Exception {
        String longest = "Abcde1!" + "x".repeat(57);

        register("long@gmail.com", longest + "x")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").value("must be 8 to 64 characters long"));
        register("long@gmail.com", longest).andExpect(status().isCreated());

        String hash = jdbc.queryForObject("SELECT password_hash FROM users", String.class);
        assertThat(passwordEncoder.matches(longest, hash)).isTrue();
    }

    @Test
    @DisplayName("missing fields are reported for each field")
    void missingFields() throws Exception {
        postJson("{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").value("must not be blank"))
                .andExpect(jsonPath("$.fieldErrors.password").value("must not be null"))
                .andExpect(jsonPath("$.fieldErrors.*", Matchers.hasSize(2)));

        assertThat(userCount()).isZero();
    }

    @Test
    @DisplayName("a password of the wrong JSON type answers 400 without repeating the value")
    void passwordOfWrongType() throws Exception {
        String body = postJson("{\"email\": \"qwe@gmail.com\", \"password\": [\"Sup3r-Secret!\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").value("has an invalid value"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(PASSWORD);
        assertThat(userCount()).isZero();
    }
}
