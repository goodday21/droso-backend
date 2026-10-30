package org.example.defect;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.example.security.AccessTokenService;
import org.example.support.PostgresIntegrationTest;
import org.example.support.TestUsers;
import org.example.user.Role;
import org.example.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/v1/defects/{id}} over a real HTTP connection to the embedded Tomcat.
 *
 * <p>MockMvc skips the servlet container, which decodes and normalizes the URL first. These tests
 * pin what a client really gets for the unusual URLs: most reach Spring MVC and get an
 * {@code ApiError}, some are rejected by Tomcat itself before.
 *
 * <p>What a client gets while PostgreSQL is down is checked by
 * {@link DefectRetrievalDatabaseDownHttpIntegrationTest}, which stops a server of its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DefectRetrievalHttpIntegrationTest extends PostgresIntegrationTest {
    private static final String NOT_FOUND =
            "{\"status\":404,\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"Resource not found\",\"fieldErrors\":{}}";
    private static final String INVALID_ID = "{\"status\":400,\"code\":\"VALIDATION_ERROR\","
            + "\"message\":\"Request validation failed\",\"fieldErrors\":{\"id\":\"has an invalid value\"}}";

    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

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

    /** The defect 26, so that an alias of its id would answer 200 instead of an error. */
    @BeforeEach
    void storeDefect26() {
        bearer = TestUsers.bearer(tokens, TestUsers.create(users, Role.USER));
        jdbc.update("INSERT INTO defects (id, title, priority, status, created_at, updated_at)"
                + " VALUES (26, 'Crash on start', 'HIGH', 'NEW', '2026-01-02T03:04:05.123456Z', '2026-01-02T03:04:05.123456Z')");
    }

    @Test
    @DisplayName("a stored defect answers 200 with the JSON of the defect")
    void existingDefect() throws Exception {
        HttpResponse<String> response = get("26");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        assertThat(jsonMapper.readTree(response.body())).isEqualTo(jsonMapper.readTree("""
                {"id": 26, "title": "Crash on start", "description": null, "stepsToReproduce": null,
                 "expectedResult": null, "actualResult": null, "priority": "HIGH", "status": "NEW", "createdBy": null,
                 "createdAt": "2026-01-02T03:04:05.123456Z", "updatedAt": "2026-01-02T03:04:05.123456Z"}
                """));
    }

    @Test
    @DisplayName("a missing defect answers 404 RESOURCE_NOT_FOUND")
    void missingDefect() throws Exception {
        HttpResponse<String> response = get("27");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(jsonMapper.readTree(response.body())).isEqualTo(jsonMapper.readTree(NOT_FOUND));
    }

    /** {@code %23} is "#", {@code %20} a space, {@code %E2%80%8B} a zero-width space. */
    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "-1", "026", "+26", "0x1A", "%231A", "%2026", "26%20",
            "%E2%80%8B26", "9223372036854775808"})
    @DisplayName("an id that is not canonical answers 400 VALIDATION_ERROR for id, also after decoding")
    void invalidId(String id) throws Exception {
        HttpResponse<String> response = get(id);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(jsonMapper.readTree(response.body())).isEqualTo(jsonMapper.readTree(INVALID_ID));
    }

    @ParameterizedTest
    @ValueSource(strings = {"26/", "26/x"})
    @DisplayName("an extra path segment answers 404 RESOURCE_NOT_FOUND")
    void extraSegment(String path) throws Exception {
        HttpResponse<String> response = get(path);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(jsonMapper.readTree(response.body())).isEqualTo(jsonMapper.readTree(NOT_FOUND));
    }

    /**
     * A path parameter ({@code ;name=value}, e.g. {@code ;jsessionid=}) would be removed from the URL
     * before the mapping, as the Servlet specification provides, so {@code 26;x=1} would be the
     * defect 26. The firewall of Spring Security rejects a semicolon, plain or encoded, before that.
     */
    @ParameterizedTest
    @ValueSource(strings = {"26;x=1", "26;jsessionid=abc", "%3B26"})
    @DisplayName("a semicolon in the path is rejected by the firewall with 400 BAD_REQUEST")
    void semicolonIsRejected(String path) throws Exception {
        HttpResponse<String> response = get(path);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(jsonMapper.readTree(response.body())).isEqualTo(jsonMapper.readTree(
                "{\"status\":400,\"code\":\"BAD_REQUEST\",\"message\":\"Bad Request\",\"fieldErrors\":{}}"));
    }

    /**
     * Pins a known limitation: Tomcat rejects an encoded slash, backslash or NUL before Spring MVC,
     * so the body is the bare error page of Tomcat instead of an {@code ApiError}. It still tells
     * nothing about the server.
     */
    @ParameterizedTest
    @ValueSource(strings = {"%2F26", "26%2F", "%5C26", "..%2F26", "26%00"})
    @DisplayName("an encoded slash, backslash or NUL is rejected by Tomcat with 400 and no details")
    void rejectedByTomcat(String path) throws Exception {
        HttpResponse<String> response = get(path);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).doesNotContainIgnoringCase("tomcat").doesNotContain("Exception")
                .doesNotContain("org.").doesNotContain("java").doesNotContainPattern("\\d+\\.\\d+\\.\\d+");
    }

    private HttpResponse<String> get(String id) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/defects/" + id))
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
