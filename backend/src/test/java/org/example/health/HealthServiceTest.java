package org.example.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.example.health.model.DatabaseHealth;
import org.example.health.model.HealthResponse;
import org.example.health.model.HealthStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HealthServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-10-22T10:15:30Z");

    private final DatabaseHealthIndicator indicator = mock(DatabaseHealthIndicator.class);
    private final HealthService service = new HealthService(indicator, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("maps an available database to the overall status UP")
    void mapsUp() {
        when(indicator.check()).thenReturn(DatabaseHealth.up(3));

        HealthResponse response = service.check();

        assertThat(response.status()).isEqualTo(HealthStatus.UP);
        assertThat(response.database().status()).isEqualTo(HealthStatus.UP);
        assertThat(response.database().latencyMs()).isEqualTo(3);
        assertThat(response.timestamp()).isEqualTo(FIXED_NOW);
    }

    @Test
    @DisplayName("maps an unavailable database to the overall status DOWN")
    void mapsDown() {
        when(indicator.check()).thenReturn(DatabaseHealth.down(2000));

        HealthResponse response = service.check();

        assertThat(response.status()).isEqualTo(HealthStatus.DOWN);
        assertThat(response.database().status()).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    @DisplayName("never propagates an unexpected failure of the indicator")
    void neverThrows() {
        when(indicator.check()).thenThrow(new IllegalStateException("boom"));

        HealthResponse response = service.check();

        assertThat(response.status()).isEqualTo(HealthStatus.DOWN);
        assertThat(response.database().status()).isEqualTo(HealthStatus.DOWN);
    }

    @Test
    @DisplayName("the response carries no technical details")
    void responseIsSanitised() {
        when(indicator.check()).thenReturn(DatabaseHealth.down(2000));

        assertThat(service.check().toString())
                .doesNotContain("jdbc:", "org.postgresql", "password", "IllegalStateException");
    }
}