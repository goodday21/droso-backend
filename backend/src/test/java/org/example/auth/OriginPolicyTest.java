package org.example.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class OriginPolicyTest {

    private final OriginPolicy policy = new OriginPolicy(new AuthProperties(true, Duration.ofDays(7),
            Duration.ofDays(30), List.of("http://localhost:4200", "https://droso.example.com")));

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:4200", "https://droso.example.com", "HTTPS://Droso.Example.com"})
    @DisplayName("an allowed origin passes, whatever the case")
    void allowed(String origin) {
        assertThatCode(() -> policy.check(origin)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "null", "http://localhost:4201", "http://localhost", "https://localhost:4200",
            "http://localhost:4200/", "https://droso.example.com.evil.io", "https://evil.droso.example.com"})
    @DisplayName("no origin, the opaque origin \"null\" and any other origin are refused")
    void refused(String origin) {
        assertThatThrownBy(() -> policy.check(origin)).isInstanceOf(OriginNotAllowedException.class);
    }

    @Test
    @DisplayName("the exception names no origin: its message stays fixed")
    void fixedMessage() {
        assertThatThrownBy(() -> policy.check("https://evil.io"))
                .hasMessage("The origin of the request is not allowed");
    }
}
