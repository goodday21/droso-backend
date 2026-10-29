package org.example.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import org.example.support.TestUsers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class JwtPropertiesTest {

    private static final Duration TTL = Duration.ofMinutes(30);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class PropertiesConfig {
    }

    private static String base64OfBytes(int length) {
        return Base64.getEncoder().encodeToString(new byte[length]);
    }

    @Test
    @DisplayName("without any droso.jwt property the context fails, naming the environment variable")
    void missingSecretFailsStartUp() {
        // The real environment is replaced by an empty one: a developer who runs the application
        // locally has DROSO_JWT_SECRET set, and the context would start with it.
        contextRunner.withInitializer(context -> context.getEnvironment().getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of()))).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("DROSO_JWT_SECRET")
                    .hasMessageContaining("openssl rand -base64 32");
        });
    }

    @Test
    @DisplayName("the environment variable DROSO_JWT_SECRET binds to droso.jwt.secret, with the defaults")
    void bindsWithDefaults() {
        // Relaxed binding maps DROSO_JWT_SECRET only from the property source of the environment.
        contextRunner.withInitializer(context -> context.getEnvironment().getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("DROSO_JWT_SECRET", TestUsers.JWT_SECRET)))).run(context -> {
            assertThat(context).hasNotFailed();
            JwtProperties properties = context.getBean(JwtProperties.class);
            assertThat(properties.issuer()).isEqualTo("droso-backend");
            assertThat(properties.accessTokenTtl()).isEqualTo(TTL);
            assertThat(properties.secretKey().getEncoded()).hasSize(32);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    @DisplayName("a blank secret is missing")
    void blankSecret(String secret) {
        assertThatIllegalArgumentException().isThrownBy(() -> new JwtProperties(secret, "droso-backend", TTL))
                .withMessageContaining("missing");
    }

    @Test
    @DisplayName("a secret of 31 bytes is too short, 32 bytes are enough")
    void minimalLength() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JwtProperties(base64OfBytes(31), "droso-backend", TTL))
                .withMessageContaining("too short");

        assertThat(new JwtProperties(base64OfBytes(32), "droso-backend", TTL).secretKey().getEncoded()).hasSize(32);
    }

    @Test
    @DisplayName("a secret that is not Base64 is rejected without quoting it")
    void notBase64() {
        String secret = "this is a long passphrase, but not Base64!";

        assertThatIllegalArgumentException().isThrownBy(() -> new JwtProperties(secret, "droso-backend", TTL))
                .withMessageContaining("not valid Base64")
                .withMessageNotContaining("passphrase")
                .withNoCause();
    }

    @Test
    @DisplayName("a blank issuer and a lifetime that is not positive are rejected")
    void issuerAndLifetime() {
        String secret = TestUsers.JWT_SECRET;

        assertThatIllegalArgumentException().isThrownBy(() -> new JwtProperties(secret, " ", TTL));
        assertThatIllegalArgumentException().isThrownBy(() -> new JwtProperties(secret, "droso-backend", Duration.ZERO));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JwtProperties(secret, "droso-backend", Duration.ofMinutes(-1)));
    }

    @Test
    @DisplayName("toString() does not reveal the secret")
    void toStringHidesTheSecret() {
        assertThat(new JwtProperties(TestUsers.JWT_SECRET, "droso-backend", TTL).toString())
                .doesNotContain(TestUsers.JWT_SECRET)
                .contains("secret=***");
    }
}
