package org.example.defect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.support.TestUsers.authorization;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import org.example.security.AccessTokenService;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /api/v1/defects} through the whole application against a real PostgreSQL server
 * whose schema was created by the Flyway migrations.
 *
 * <p>What was stored is read back with plain JDBC, independently of JPA and of the response. Every
 * rejected request is checked to leave the table empty.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DefectCreationIntegrationTest extends PostgresIntegrationTest {
    private static final String URL = "/api/v1/defects";

    /** One code point, two UTF-16 units. */
    private static final String EMOJI = "😀";

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

    /** The id of the user who sends the requests, a USER (who may report defects). */
    private long author;

    /** The header Authorization of that user. */
    private String bearer;

    @BeforeEach
    void logIn() {
        User user = TestUsers.create(users, Role.USER);
        author = user.getId();
        bearer = TestUsers.bearer(tokens, user);
    }

    // ---------------------------------------------------------------- successful creation

    @Test
    @DisplayName("a full request is stored and answered with 201, the stored defect and its Location")
    void createsFullDefect() throws Exception {
        // createdAt is truncated to microseconds, the clock is not: an untruncated "before" may lie
        // inside the same microsecond, after the stored value.
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String body = mockMvc.perform(post(URL).with(authorization(bearer)).contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "title": "Ошибка при сохранении профиля",
                          "description": "Изменения не сохраняются после редактирования профиля",
                          "stepsToReproduce": "1. Открыть профиль\\n2. Изменить имя\\n3. Нажать Сохранить",
                          "expectedResult": "Изменённое имя сохранено",
                          "actualResult": "Отображается старое имя",
                          "priority": "HIGH"
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Content-Type", Matchers.startsWith(MediaType.APPLICATION_JSON_VALUE)))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.title").value("Ошибка при сохранении профиля"))
                .andExpect(jsonPath("$.description").value("Изменения не сохраняются после редактирования профиля"))
                .andExpect(jsonPath("$.stepsToReproduce").value("1. Открыть профиль\n2. Изменить имя\n3. Нажать Сохранить"))
                .andExpect(jsonPath("$.expectedResult").value("Изменённое имя сохранено"))
                .andExpect(jsonPath("$.actualResult").value("Отображается старое имя"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.createdBy").value(author))
                .andExpect(jsonPath("$.*", Matchers.hasSize(11)))
                .andReturn().getResponse().getContentAsString();
        Instant after = Instant.now();

        long id = id(body);
        Instant createdAt = Instant.parse(JsonPath.read(body, "$.createdAt"));
        Instant updatedAt = Instant.parse(JsonPath.read(body, "$.updatedAt"));
        assertThat(createdAt).isBetween(before, after);
        assertThat(updatedAt).isEqualTo(createdAt);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM defects WHERE id = ?", id);
        assertThat(row.get("title")).isEqualTo("Ошибка при сохранении профиля");
        assertThat(row.get("description")).isEqualTo("Изменения не сохраняются после редактирования профиля");
        assertThat(row.get("steps_to_reproduce")).isEqualTo("1. Открыть профиль\n2. Изменить имя\n3. Нажать Сохранить");
        assertThat(row.get("expected_result")).isEqualTo("Изменённое имя сохранено");
        assertThat(row.get("actual_result")).isEqualTo("Отображается старое имя");
        assertThat(row.get("priority")).isEqualTo("HIGH");
        assertThat(row.get("status")).isEqualTo("NEW");
        assertThat(row.get("created_by")).isEqualTo(author);
        assertThat(timestamp("created_at", id)).isEqualTo(createdAt);
        assertThat(timestamp("updated_at", id)).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("the Location points to /api/v1/defects/{id} of the created defect")
    void locationPointsToTheDefect() throws Exception {
        ResultActions result = create(Map.of("title", "Login fails")).andExpect(status().isCreated());

        long id = id(result.andReturn().getResponse().getContentAsString());
        result.andExpect(header().string("Location", "/api/v1/defects/" + id));
    }

    @Test
    @DisplayName("a request with the title only gets MEDIUM, NEW, empty optional fields and equal timestamps")
    void createsMinimalDefect() throws Exception {
        String body = body(create(Map.of("title", "Crash on start"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Crash on start"))
                .andExpect(jsonPath("$.priority").value("MEDIUM"))
                .andExpect(jsonPath("$.status").value("NEW"))
                // The optional fields are present in the response, as null.
                .andExpect(jsonPath("$.description").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.stepsToReproduce").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.expectedResult").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.actualResult").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.createdBy").value(author))
                .andExpect(jsonPath("$.*", Matchers.hasSize(11))));

        assertThat((String) JsonPath.read(body, "$.updatedAt")).isEqualTo(JsonPath.read(body, "$.createdAt"));
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM defects WHERE id = ?", id(body));
        assertThat(row.get("priority")).isEqualTo("MEDIUM");
        assertThat(row.get("status")).isEqualTo("NEW");
        assertThat(row.get("description")).isNull();
        assertThat(row.get("steps_to_reproduce")).isNull();
        assertThat(row.get("expected_result")).isNull();
        assertThat(row.get("actual_result")).isNull();
    }

    @Test
    @DisplayName("an explicit null priority is MEDIUM as well")
    void nullPriorityIsMedium() throws Exception {
        create(fields("title", "Crash on start", "priority", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priority").value("MEDIUM"));
    }

    @ParameterizedTest
    @EnumSource(DefectPriority.class)
    @DisplayName("every priority is accepted and stored")
    void everyPriority(DefectPriority priority) throws Exception {
        String body = body(create(Map.of("title", "Crash on start", "priority", priority.name()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priority").value(priority.name())));

        assertThat(jdbc.queryForObject("SELECT priority FROM defects WHERE id = ?", String.class, id(body)))
                .isEqualTo(priority.name());
    }

    @Test
    @DisplayName("every defect gets its own id from the database")
    void idsAreGenerated() throws Exception {
        long first = id(body(create(Map.of("title", "First defect")).andExpect(status().isCreated())));
        long second = id(body(create(Map.of("title", "Second defect")).andExpect(status().isCreated())));

        assertThat(second).isNotEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM defects", Long.class)).isEqualTo(2);
    }

    // ---------------------------------------------------------------- title
    //
    // The rules of the title are pinned by CreateDefectRequestTest; here one case per kind of error
    // checks how a violation reaches the client and that the stored value is the normalized one.

    @Test
    @DisplayName("the surrounding whitespace of the title is stripped before it is stored")
    void titleIsStripped() throws Exception {
        String body = body(create(Map.of("title", " \t Login fails \n "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Login fails")));

        assertThat(jdbc.queryForObject("SELECT title FROM defects WHERE id = ?", String.class, id(body)))
                .isEqualTo("Login fails");
    }

    @Test
    @DisplayName("a missing title is rejected")
    void missingTitleIsRejected() throws Exception {
        rejected(Map.of("description", "No title"), "title", "must not be blank");
    }

    @Test
    @DisplayName("a title of invisible characters reports both violated rules in one message, sorted")
    void blankTitleIsRejected() throws Exception {
        rejected(Map.of("title", "\u00A0 \u200B"), "title", "length must be between 5 and 120; must not be blank");
    }

    // ---------------------------------------------------------------- optional text fields

    @ParameterizedTest(name = "{0}: {1} characters")
    @CsvSource({
            "description,      2000, description",
            "stepsToReproduce, 5000, steps_to_reproduce",
            "expectedResult,   2000, expected_result",
            "actualResult,     2000, actual_result"})
    @DisplayName("an optional field of its maximal length is stored completely")
    void optionalFieldOfMaximalLength(String field, int max, String column) throws Exception {
        String value = "x".repeat(max - 1) + EMOJI;
        String body = body(create(Map.of("title", "Crash on start", field, value))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$." + field).value(value)));

        assertThat(jdbc.queryForObject("SELECT " + column + " FROM defects WHERE id = ?", String.class, id(body)))
                .isEqualTo(value);
    }

    @Test
    @DisplayName("an optional field of invisible characters only is stored as null, however long")
    void invisibleOptionalFieldIsNull() throws Exception {
        String body = body(create(Map.of("title", "Crash on start", "description", " \u200B" + " ".repeat(6000)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").value(Matchers.nullValue())));

        assertThat(jdbc.queryForObject("SELECT description FROM defects WHERE id = ?", String.class, id(body)))
                .isNull();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "description,      description",
            "stepsToReproduce, steps_to_reproduce",
            "expectedResult,   expected_result",
            "actualResult,     actual_result"})
    @DisplayName("an optional field with visible text is stored exactly as sent, whitespace included")
    void optionalFieldIsStoredAsSent(String field, String column) throws Exception {
        String value = "  1. abc\n2. абв\n\n3.PROFIT \n";
        String body = body(create(Map.of("title", "Crash on start", field, value))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$." + field).value(value)));

        assertThat(jdbc.queryForObject("SELECT " + column + " FROM defects WHERE id = ?", String.class, id(body)))
                .isEqualTo(value);
    }

    @Test
    @DisplayName("every invalid field is reported at once, sorted by name")
    void severalInvalidFields() throws Exception {
        String body = body(create(Map.of("title", "abc", "description", "x".repeat(2001),
                "actualResult", "x".repeat(2001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.*", Matchers.hasSize(3))));

        assertThat(body).containsSubsequence("\"actualResult\"", "\"description\"", "\"title\"");
        assertNothingStored();
    }

    // ---------------------------------------------------------------- priority

    @ParameterizedTest
    @ValueSource(strings = {"\"URGENT\"", "\"high\"", "\"\"", "2", "\"2\"", "true", "{}"})
    @DisplayName("an unknown priority, a lowercase one, an index or a value of another type is rejected")
    void invalidPriorityIsRejected(String priority) throws Exception {
        String body = body(postJson("{\"title\": \"Crash on start\", \"priority\": " + priority + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.priority").value("has an invalid value"))
                .andExpect(jsonPath("$.fieldErrors.*", Matchers.hasSize(1))));

        assertThat(body).doesNotContain("URGENT").doesNotContain("DefectPriority").doesNotContain("org.example");
        assertNothingStored();
    }

    // ---------------------------------------------------------------- server-side fields

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "id        | 42",
            "status    | \"NEW\"",
            "createdAt | \"2020-01-01T00:00:00Z\"",
            "updatedAt | \"2020-01-01T00:00:00Z\""})
    @DisplayName("a field chosen by the server is rejected rather than ignored")
    void serverSideFieldIsRejected(String field, String value) throws Exception {
        postJson("{\"title\": \"Crash on start\", \"" + field + "\": " + value + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors." + field).value("is not allowed"))
                .andExpect(jsonPath("$.fieldErrors.*", Matchers.hasSize(1)));

        assertNothingStored();
    }

    @Test
    @DisplayName("an unknown field, e.g. a misspelt one, is rejected rather than ignored")
    void unknownFieldIsRejected() throws Exception {
        rejected(Map.of("title", "Crash on start", "priorty", "HIGH"), "priorty", "is not allowed");
    }

    // ---------------------------------------------------------------- malformed requests

    @ParameterizedTest
    @ValueSource(strings = {"{\"title\": ", "{\"title\": \"Crash on start\",}", "not json", "",
            "[{\"title\": \"Crash on start\"}]", "\"Crash on start\"", "{\"title\": \"Crash on start\"} {}"})
    @DisplayName("an invalid JSON, a missing body or a body that is not an object answers 400 MALFORMED_REQUEST")
    void malformedBody(String content) throws Exception {
        String body = body(postJson(content)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("Malformed request body"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.*", Matchers.hasSize(4))));

        assertThat(body).doesNotContain("JSON parse").doesNotContain("jackson").doesNotContain("line:");
        assertNothingStored();
    }

    @Test
    @DisplayName("a title of the wrong JSON type is rejected for the field")
    void titleOfWrongType() throws Exception {
        postJson("{\"title\": [\"Crash on start\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.title").value("has an invalid value"));

        assertNothingStored();
    }

    @Test
    @DisplayName("a body that is not JSON answers 415 UNSUPPORTED_MEDIA_TYPE")
    void notJson() throws Exception {
        mockMvc.perform(post(URL).with(authorization(bearer)).contentType(MediaType.TEXT_PLAIN).content("Crash on start"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        assertNothingStored();
    }

    // ---------------------------------------------------------------- error format

    @Test
    @DisplayName("a validation error has exactly status, code, message and fieldErrors")
    void validationErrorFormat() throws Exception {
        create(Map.of("title", ""))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", Matchers.startsWith(MediaType.APPLICATION_JSON_VALUE)))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.fieldErrors").isMap())
                .andExpect(jsonPath("$.*", Matchers.hasSize(4)))
                .andExpect(header().doesNotExist("Location"));
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions create(Map<String, ?> fields) throws Exception {
        return postJson(jsonMapper.writeValueAsString(fields));
    }

    private ResultActions postJson(String content) throws Exception {
        return mockMvc.perform(post(URL).with(authorization(bearer)).contentType(MediaType.APPLICATION_JSON).content(content));
    }

    /** Asserts a 400 VALIDATION_ERROR with exactly one field error, and that nothing was stored. */
    private void rejected(Map<String, ?> fields, String field, String message) throws Exception {
        create(fields)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.fieldErrors." + field).value(message))
                .andExpect(jsonPath("$.fieldErrors.*", Matchers.hasSize(1)));
        assertNothingStored();
    }

    private void assertNothingStored() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM defects", Long.class)).isZero();
    }

    /** Builds a map that may hold null values, which {@link Map#of} does not allow. */
    private static Map<String, Object> fields(Object... keysAndValues) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            fields.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return fields;
    }

    private Instant timestamp(String column, long id) {
        return jdbc.queryForObject("SELECT " + column + " FROM defects WHERE id = ?", OffsetDateTime.class, id)
                .toInstant();
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private static long id(String body) {
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }
}
