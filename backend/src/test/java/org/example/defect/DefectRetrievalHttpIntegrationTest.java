package org.example.defect;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/v1/defects/{id}} over a real HTTP connection to the embedded Tomcat.
 *
 * <p>MockMvc skips the servlet container, which decodes and normalizes the URL first. These tests
 * pin what a client really gets for the unusual URLs: most reach Spring MVC and get an
 * {@code ApiError}, some are rejected by Tomcat itself before.
 *
 * <p>The last test stops PostgreSQL, so the methods run in a fixed order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DefectRetrievalHttpIntegrationTest {

    private static final String NOT_FOUND =
            "{\"status\":404,\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"Resource not found\",\"fieldErrors\":{}}";
    private static final String INVALID_ID = "{\"status\":400,\"code\":\"VALIDATION_ERROR\","
            + "\"message\":\"Request validation failed\",\"fieldErrors\":{\"id\":\"has an invalid value\"}}";

    private static EmbeddedPostgres postgres;

    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder()
                .setPort(0)
                // The server inherits the OS locale on Windows and therefore answers in Russian.
                // "C" keeps every server-side message in English.
                .setServerConfig("lc_messages", "C")
                .start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    /** The defect 26, so that an alias of its id would answer 200 instead of an error. */
    @BeforeEach
    void storeDefect26() {
        jdbc.update("DELETE FROM defects");
        jdbc.update("INSERT INTO defects (id, title, priority, status, created_at, updated_at)"
                + " VALUES (26, 'Crash on start', 'HIGH', 'NEW', '2026-01-02T03:04:05.123456Z', '2026-01-02T03:04:05.123456Z')");
    }

    @Test
    @Order(1)
    @DisplayName("a stored defect answers 200 with the JSON of the defect")
    void existingDefect() throws Exception {
        HttpResponse<String> response = get("26");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        assertThat(jsonMapper.readTree(response.body())).isEqualTo(jsonMapper.readTree("""
                {"id": 26, "title": "Crash on start", "description": null, "stepsToReproduce": null,
                 "expectedResult": null, "actualResult": null, "priority": "HIGH", "status": "NEW",
                 "createdAt": "2026-01-02T03:04:05.123456Z", "updatedAt": "2026-01-02T03:04:05.123456Z"}
                """));
    }

    @Test
    @Order(2)
    @DisplayName("a missing defect answers 404 RESOURCE_NOT_FOUND")
    void missingDefect() throws Exception {
        HttpResponse<String> response = get("27");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(jsonMapper.readTree(response.body())).isEqualTo(jsonMapper.readTree(NOT_FOUND));
    }

    /** {@code %23} is "#", {@code %20} a space, {@code %3B} ";", {@code %E2%80%8B} a zero-width space. */
    @ParameterizedTest
    @Order(3)
    @ValueSource(strings = {"abc", "0", "-1", "026", "+26", "0x1A", "%231A", "%2026", "26%20", "%3B26",
            "%E2%80%8B26", "9223372036854775808"})
    @DisplayName("an id that is not canonical answers 400 VALIDATION_ERROR for id, also after decoding")
    void invalidId(String id) throws Exception {
        HttpResponse<String> response = get(id);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(jsonMapper.readTree(response.body())).isEqualTo(jsonMapper.readTree(INVALID_ID));
    }

    @ParameterizedTest
    @Order(4)
    @ValueSource(strings = {"26/", "26/x"})
    @DisplayName("an extra path segment answers 404 RESOURCE_NOT_FOUND")
    void extraSegment(String path) throws Exception {
        HttpResponse<String> response = get(path);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(jsonMapper.readTree(response.body())).isEqualTo(jsonMapper.readTree(NOT_FOUND));
    }

    /**
     * Pins a known limitation: a path parameter ({@code ;name=value}, e.g. {@code ;jsessionid=})
     * is removed from the URL before the mapping, as the Servlet specification provides, so the id
     * is still the canonical {@code 26}.
     */
    @ParameterizedTest
    @Order(5)
    @ValueSource(strings = {"26;x=1", "26;jsessionid=abc"})
    @DisplayName("a path parameter after the id is ignored (Servlet behaviour)")
    void pathParameterIsIgnored(String path) throws Exception {
        HttpResponse<String> response = get(path);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"id\":26");
    }

    /**
     * Pins a known limitation: Tomcat rejects an encoded slash, backslash or NUL before Spring MVC,
     * so the body is the bare error page of Tomcat instead of an {@code ApiError}. It still tells
     * nothing about the server.
     */
    @ParameterizedTest
    @Order(6)
    @ValueSource(strings = {"%2F26", "26%2F", "%5C26", "..%2F26", "26%00"})
    @DisplayName("an encoded slash, backslash or NUL is rejected by Tomcat with 400 and no details")
    void rejectedByTomcat(String path) throws Exception {
        HttpResponse<String> response = get(path);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).doesNotContainIgnoringCase("tomcat").doesNotContain("Exception")
                .doesNotContain("org.").doesNotContain("java").doesNotContainPattern("\\d+\\.\\d+\\.\\d+");
    }

    @Test
    @Order(Integer.MAX_VALUE)
    @DisplayName("a GET while PostgreSQL is down answers 500 INTERNAL_ERROR without SQL or database details")
    void databaseDown() throws Exception {
        postgres.close();

        HttpResponse<String> response = get("26");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(jsonMapper.readTree(response.body())).isEqualTo(jsonMapper.readTree(
                "{\"status\":500,\"code\":\"INTERNAL_ERROR\",\"message\":\"Internal server error\",\"fieldErrors\":{}}"));
    }

    private HttpResponse<String> get(String id) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/defects/" + id))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
