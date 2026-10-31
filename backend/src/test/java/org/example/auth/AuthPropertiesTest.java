package org.example.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class AuthPropertiesTest {

    private static final Duration TTL = Duration.ofDays(7);
    private static final Duration MAX = Duration.ofDays(30);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthProperties.class)
    static class PropertiesConfig {
    }

    private static AuthProperties properties(Duration ttl, Duration max, List<String> origins) {
        return new AuthProperties(true, ttl, max, origins);
    }

    /** Replaces the real environment, whose DROSO_AUTH_* a developer may have set. */
    private ApplicationContextRunner withEnvironment(Map<String, Object> variables) {
        return contextRunner.withInitializer(context -> context.getEnvironment().getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        variables)));
    }

    @Test
    @DisplayName("without settings: registration on, 7 days, 30 days, the development server of the frontend")
    void defaults() {
        withEnvironment(Map.of()).run(context -> {
            AuthProperties properties = context.getBean(AuthProperties.class);
            assertThat(properties.registrationEnabled()).isTrue();
            assertThat(properties.refreshTokenTtl()).isEqualTo(TTL);
            assertThat(properties.refreshTokenMaxLifetime()).isEqualTo(MAX);
            assertThat(properties.allowedOrigins()).containsExactly("http://localhost:4200");
        });
    }

    @Test
    @DisplayName("DROSO_AUTH_ALLOWED_ORIGINS is a comma-separated list, normalized to lower case")
    void originsFromEnvironment() {
        withEnvironment(Map.of("DROSO_AUTH_ALLOWED_ORIGINS", "https://Droso.Example.com, http://localhost:4200"))
                .run(context -> assertThat(context.getBean(AuthProperties.class).allowedOrigins())
                        .containsExactly("https://droso.example.com", "http://localhost:4200"));
    }

    @Test
    @DisplayName("an empty DROSO_AUTH_ALLOWED_ORIGINS fails the start-up, naming the property")
    void emptyOriginsFailStartUp() {
        withEnvironment(Map.of("DROSO_AUTH_ALLOWED_ORIGINS", "")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("droso.auth.allowed-origins");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:4200", "https://droso.example.com", "http://127.0.0.1:8080",
            "https://[::1]:4200"})
    @DisplayName("an origin is scheme://host[:port]")
    void validOrigins(String origin) {
        assertThat(properties(TTL, MAX, List.of(origin)).allowedOrigins()).containsExactly(origin);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "*", "null", "localhost:4200", "droso.example.com", "ftp://droso.example.com",
            "http://localhost:4200/", "https://droso.example.com/app", "https://droso.example.com?x=1",
            "https://droso.example.com#x", "https://user@droso.example.com", "http://", "http://local host"})
    @DisplayName("anything else fails the start-up: a path, a trailing slash, a wildcard, a missing scheme")
    void invalidOrigins(String origin) {
        assertThatIllegalArgumentException().isThrownBy(() -> properties(TTL, MAX, List.of(origin)))
                .withMessageContaining("droso.auth.allowed-origins");
    }

    @Test
    @DisplayName("the lifetimes must be positive, and the maximum not shorter than one token")
    void lifetimes() {
        List<String> origins = List.of("http://localhost:4200");
        assertThatIllegalArgumentException().isThrownBy(() -> properties(Duration.ZERO, MAX, origins));
        assertThatIllegalArgumentException().isThrownBy(() -> properties(Duration.ofDays(-1), MAX, origins));
        assertThatIllegalArgumentException().isThrownBy(() -> properties(null, MAX, origins));
        assertThatIllegalArgumentException().isThrownBy(() -> properties(TTL, Duration.ofDays(6), origins))
                .withMessageContaining("droso.auth.refresh-token-max-lifetime");
        assertThatIllegalArgumentException().isThrownBy(() -> properties(TTL, null, origins));
        assertThat(properties(TTL, TTL, origins).refreshTokenMaxLifetime()).isEqualTo(TTL);
    }

    @Test
    @DisplayName("no origin at all fails")
    void noOrigins() {
        assertThatIllegalArgumentException().isThrownBy(() -> properties(TTL, MAX, List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> properties(TTL, MAX, null));
    }
}
