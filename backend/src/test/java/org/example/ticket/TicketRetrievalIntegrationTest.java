package org.example.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.support.TestUsers.authorization;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import org.example.security.AccessTokenService;
import org.example.support.PostgresIntegrationTest;
import org.example.support.TestUsers;
import org.example.user.Role;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/v1/tickets/{id}} through the whole application against a real PostgreSQL server
 * whose schema was created by the Flyway migrations.
 *
 * <p>Most tickets are inserted with plain JDBC, independently of JPA and of the POST endpoint, so
 * that every column, including two different timestamps, is known exactly. That the GET writes
 * nothing is checked on the row itself, including its system column {@code xmin}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TicketRetrievalIntegrationTest extends PostgresIntegrationTest {
    private static final String URL = "/api/v1/tickets";

    /** ISO-8601 in UTC; {@code Instant} drops the trailing zero groups of the fraction. */
    private static final String ISO_INSTANT = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{3}|\\.\\d{6})?Z";

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

    /** The header Authorization of a user with the role USER. */
    private String bearer;

    @BeforeEach
    void logIn() {
        bearer = TestUsers.bearer(tokens, TestUsers.create(users, Role.USER));
    }

    // ---------------------------------------------------------------- existing ticket

    @Test
    @DisplayName("a stored ticket is answered with 200 and every column of its row")
    void returnsStoredTicket() throws Exception {
        long id = insert("Profile changes are not saved", "Изменения теряются", "1. Open\n2. Save",
                "Saved", "Not saved 😀", "CRITICAL",
                "2026-01-02T03:04:05.123456Z", "2026-03-04T05:06:07.654321Z");
        jdbc.update("UPDATE tickets SET type = 'BUG', status = 'WAITING_FOR_FIX' WHERE id = ?", id);

        mockMvc.perform(get(URL + "/{id}", id).with(authorization(bearer)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", Matchers.startsWith(MediaType.APPLICATION_JSON_VALUE)))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.title").value("Profile changes are not saved"))
                .andExpect(jsonPath("$.description").value("Изменения теряются"))
                .andExpect(jsonPath("$.stepsToReproduce").value("1. Open\n2. Save"))
                .andExpect(jsonPath("$.expectedResult").value("Saved"))
                .andExpect(jsonPath("$.actualResult").value("Not saved 😀"))
                .andExpect(jsonPath("$.priority").value("CRITICAL"))
                .andExpect(jsonPath("$.status").value("WAITING_FOR_FIX"))
                .andExpect(jsonPath("$.type").value("BUG"))
                .andExpect(jsonPath("$.createdAt").value("2026-01-02T03:04:05.123456Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-03-04T05:06:07.654321Z"))
                .andExpect(jsonPath("$.createdBy").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.*", Matchers.hasSize(12)));
    }

    @Test
    @DisplayName("the author is answered as the id of the user; a ticket from before V6 has none (null)")
    void author() throws Exception {
        long author = TestUsers.create(users, Role.MANAGER).getId();
        long id = insert("Crash on start", null, null, null, null, "LOW",
                "2026-01-02T03:04:05Z", "2026-01-02T03:04:05Z");
        jdbc.update("UPDATE tickets SET created_by = ? WHERE id = ?", author, id);

        mockMvc.perform(get(URL + "/{id}", id).with(authorization(bearer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdBy").value(author));
    }

    @Test
    @DisplayName("the optional fields that are not set are present as null")
    void optionalFieldsAreNull() throws Exception {
        long id = insert("Crash on start", null, null, null, null, "MEDIUM",
                "2026-01-02T03:04:05Z", "2026-01-02T03:04:05Z");

        mockMvc.perform(get(URL + "/{id}", id).with(authorization(bearer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.stepsToReproduce").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.expectedResult").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.actualResult").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.*", Matchers.hasSize(12)));
    }

    @Test
    @DisplayName("the timestamps are ISO-8601 in UTC, whatever the offset they were written with")
    void timestampsAreIsoUtc() throws Exception {
        // Written with +03:00; TIMESTAMPTZ stores the instant, the API answers it in UTC.
        long id = insert("Crash on start", null, null, null, null, "LOW",
                "2026-06-30T23:30:00.120+03:00", "2026-07-01T00:00:00.000001+03:00");

        String body = body(mockMvc.perform(get(URL + "/{id}", id).with(authorization(bearer))).andExpect(status().isOk()));

        assertThat((String) JsonPath.read(body, "$.createdAt")).matches(ISO_INSTANT).isEqualTo("2026-06-30T20:30:00.120Z");
        assertThat((String) JsonPath.read(body, "$.updatedAt")).matches(ISO_INSTANT).isEqualTo("2026-06-30T21:00:00.000001Z");
    }

    @Test
    @DisplayName("each id answers its own ticket")
    void eachIdAnswersItsTicket() throws Exception {
        long first = insert("First ticket", null, null, null, null, "LOW", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
        long second = insert("Second ticket", null, null, null, null, "HIGH", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");

        mockMvc.perform(get(URL + "/{id}", first).with(authorization(bearer))).andExpect(jsonPath("$.title").value("First ticket"));
        mockMvc.perform(get(URL + "/{id}", second).with(authorization(bearer))).andExpect(jsonPath("$.title").value("Second ticket"));
    }

    // ---------------------------------------------------------------- POST, then GET

    @Test
    @DisplayName("a ticket created with POST is read back by a separate GET of its Location")
    void createdTicketIsReadBack() throws Exception {
        ResultActions created = mockMvc.perform(post(URL).with(authorization(bearer)).contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "title": "  Login fails  ",
                          "description": "The button does nothing",
                          "stepsToReproduce": "1. Open the login page\\n2. Click Login",
                          "expectedResult": "Logged in",
                          "actualResult": "Nothing happens",
                          "priority": "HIGH"
                        }
                        """))
                .andExpect(status().isCreated());
        String location = created.andReturn().getResponse().getHeader("Location");
        String postBody = body(created);

        String getBody = body(mockMvc.perform(get(location).with(authorization(bearer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Login fails"))
                .andExpect(jsonPath("$.status").value("NEW")));

        assertThat(jsonMapper.readTree(getBody)).isEqualTo(jsonMapper.readTree(postBody));
        assertThat((String) JsonPath.read(getBody, "$.createdAt")).matches(ISO_INSTANT);
        assertThat(Instant.parse(JsonPath.read(getBody, "$.createdAt"))).isEqualTo(timestamp("created_at", id(getBody)));
        assertThat(Instant.parse(JsonPath.read(getBody, "$.updatedAt"))).isEqualTo(timestamp("updated_at", id(getBody)));
    }

    // ---------------------------------------------------------------- GET is read-only

    @Test
    @DisplayName("repeated GETs leave the row untouched: same columns, same xmin, no new rows")
    void getChangesNothing() throws Exception {
        long id = insert("Crash on start", "Text", null, null, null, "HIGH",
                "2026-01-02T03:04:05.123456Z", "2026-03-04T05:06:07.654321Z");
        Map<String, Object> before = row(id);

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get(URL + "/{id}", id).with(authorization(bearer))).andExpect(status().isOk());
        }

        // xmin is the id of the transaction that wrote the row version: an UPDATE, even one that
        // writes the same values, would change it.
        assertThat(row(id)).isEqualTo(before);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a GET of a missing id does not create it")
    void getOfMissingIdCreatesNothing() throws Exception {
        long id = insert("Crash on start", null, null, null, null, "HIGH", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
        Map<String, Object> before = row(id);

        mockMvc.perform(get(URL + "/{id}", id + 1).with(authorization(bearer))).andExpect(status().isNotFound());

        assertThat(count()).isEqualTo(1);
        assertThat(row(id)).isEqualTo(before);
    }

    // ---------------------------------------------------------------- missing ticket

    @ParameterizedTest
    @ValueSource(longs = {1, 424242, Long.MAX_VALUE})
    @DisplayName("a missing ticket answers 404 RESOURCE_NOT_FOUND without naming the resource or the id")
    void missingTicket(long id) throws Exception {
        String body = body(mockMvc.perform(get(URL + "/{id}", id).with(authorization(bearer)))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", Matchers.startsWith(MediaType.APPLICATION_JSON_VALUE)))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.*", Matchers.hasSize(4))));

        // The whole body is known: nothing else (the id, SQL, a class name) can be in it.
        assertThat(jsonMapper.readTree(body)).isEqualTo(jsonMapper.readTree(
                "{\"status\":404,\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"Resource not found\",\"fieldErrors\":{}}"));
    }

    @Test
    @DisplayName("a removed row is not found any more")
    void removedTicket() throws Exception {
        long id = insert("Crash on start", null, null, null, null, "HIGH", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
        jdbc.update("DELETE FROM tickets WHERE id = ?", id);

        mockMvc.perform(get(URL + "/{id}", id).with(authorization(bearer)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("a soft-deleted ticket answers 404 RESOURCE_NOT_FOUND, although its row stays")
    void softDeletedTicket() throws Exception {
        long id = insert("Crash on start", null, null, null, null, "HIGH", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
        jdbc.update("UPDATE tickets SET deleted_at = now() WHERE id = ?", id);

        mockMvc.perform(get(URL + "/{id}", id).with(authorization(bearer)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertThat(count()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- invalid id

    /**
     * Not only what cannot be a number, but also what the lenient default conversion of Spring
     * would turn into another id ({@code 0x1A} and {@code 026} would be the ticket 26).
     */
    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "1e3", "1A", "9223372036854775808", "99999999999999999999",
            "0", "-1", "+26", "026", "0x1A", "#1A", " 26", "26 ", "١"})
    @DisplayName("an id that is not a canonical positive number answers 400 VALIDATION_ERROR for id")
    void invalidId(String id) throws Exception {
        // The ticket 26 exists, so that an alias of it would answer 200 instead of 400.
        jdbc.update("INSERT INTO tickets (id, title, priority, status, created_at, updated_at)"
                + " VALUES (26, 'Crash on start', 'HIGH', 'NEW', now(), now())");

        String body = body(mockMvc.perform(get(URL + "/{id}", id).with(authorization(bearer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.fieldErrors.id").value("has an invalid value"))
                .andExpect(jsonPath("$.fieldErrors.*", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.*", Matchers.hasSize(4))));

        // The whole body is known: neither the rejected value nor a Java type can be in it.
        assertThat(jsonMapper.readTree(body)).isEqualTo(jsonMapper.readTree("{\"status\":400,"
                + "\"code\":\"VALIDATION_ERROR\",\"message\":\"Request validation failed\","
                + "\"fieldErrors\":{\"id\":\"has an invalid value\"}}"));
    }

    @Test
    @DisplayName("the collection URL with a trailing slash answers 404 RESOURCE_NOT_FOUND")
    void trailingSlash() throws Exception {
        mockMvc.perform(get(URL + "/").with(authorization(bearer)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET of the collection is not implemented and answers 405 METHOD_NOT_ALLOWED")
    void collectionIsNotReadable() throws Exception {
        mockMvc.perform(get(URL).with(authorization(bearer)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.*", Matchers.hasSize(4)));
    }

    // ---------------------------------------------------------------- helpers

    private long insert(String title, String description, String steps, String expected, String actual,
            String priority, String createdAt, String updatedAt) {
        return jdbc.queryForObject("""
                INSERT INTO tickets (title, description, steps_to_reproduce, expected_result, actual_result,
                                     priority, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'NEW', ?, ?)
                RETURNING id
                """, Long.class, title, description, steps, expected, actual, priority,
                OffsetDateTime.parse(createdAt), OffsetDateTime.parse(updatedAt));
    }

    /** Every column of the row plus the id of the transaction that wrote it. */
    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT xmin::text AS xmin, * FROM tickets WHERE id = ?", id);
    }

    private long count() {
        return jdbc.queryForObject("SELECT count(*) FROM tickets", Long.class);
    }

    private Instant timestamp(String column, long id) {
        return jdbc.queryForObject("SELECT " + column + " FROM tickets WHERE id = ?", OffsetDateTime.class, id)
                .toInstant();
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private static long id(String body) {
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }
}
