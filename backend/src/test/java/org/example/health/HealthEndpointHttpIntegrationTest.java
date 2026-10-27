package org.example.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.example.support.PostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Checks the endpoint over a real HTTP connection against a running server.
 *
 * <p>Unlike the MockMvc based test, this test verifies the status codes as they are actually
 * transmitted over the network, which is what an orchestrator or a load balancer observes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointHttpIntegrationTest extends PostgresIntegrationTest {
    @LocalServerPort
    private int port;

    @Test
    @DisplayName("responds with HTTP 200 while PostgreSQL is running")
    void respondsWith200() throws Exception {
        HttpResponse<String> response = call();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("responds with HTTP 503 after PostgreSQL has been stopped")
    void respondsWith503() throws Exception {
        postgres().close();

        HttpResponse<String> response = call();

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("\"status\":\"DOWN\"");
        assertThat(response.body()).doesNotContain("jdbc:", "org.postgresql", "Exception");
    }

    private HttpResponse<String> call() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/health"))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}