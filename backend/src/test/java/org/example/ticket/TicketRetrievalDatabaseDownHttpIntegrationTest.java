package org.example.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.example.security.AccessTokenService;
import org.example.support.IsolatedPostgresIntegrationTest;
import org.example.support.TestUsers;
import org.example.user.Role;
import org.example.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/v1/tickets/{id}} over a real HTTP connection while PostgreSQL is down. A class of
 * its own, because it stops the server: see {@link IsolatedPostgresIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TicketRetrievalDatabaseDownHttpIntegrationTest extends IsolatedPostgresIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private UserRepository users;

    @Autowired
    private AccessTokenService tokens;

    /**
     * The user of the token is loaded before the controller runs, so the failure happens in the
     * filter chain; it is still answered as an {@code ApiError} (ApiAuthenticationFailureHandler).
     */
    @Test
    @DisplayName("a GET while PostgreSQL is down answers 500 INTERNAL_ERROR without SQL or database details")
    void databaseDown() throws Exception {
        String bearer = TestUsers.bearer(tokens, TestUsers.create(users, Role.USER));
        postgres().close();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/tickets/26"))
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(jsonMapper.readTree(response.body())).isEqualTo(jsonMapper.readTree(
                "{\"status\":500,\"code\":\"INTERNAL_ERROR\",\"message\":\"Internal server error\",\"fieldErrors\":{}}"));
    }
}
