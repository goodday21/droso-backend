package org.example.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.example.auth.RefreshTokenService.IssuedRefreshToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefreshCookieTest {

    private static final String TOKEN = "a".repeat(43);

    @Test
    @DisplayName("the cookie is HttpOnly, Secure, SameSite=Strict, limited to /api/v1/auth and lives as long as the token")
    void cookie() {
        String cookie = RefreshCookie.of(new IssuedRefreshToken(TOKEN, Duration.ofDays(7)));

        assertThat(cookie).startsWith("droso_refresh=" + TOKEN + "; Path=/api/v1/auth; Max-Age=604800; Expires=")
                .endsWith("; Secure; HttpOnly; SameSite=Strict");
    }

    @Test
    @DisplayName("the cleared cookie has the same name and path, no value and Max-Age=0, so that the browser deletes it")
    void cleared() {
        assertThat(RefreshCookie.cleared())
                .startsWith("droso_refresh=; Path=/api/v1/auth; Max-Age=0; Expires=")
                .endsWith("; Secure; HttpOnly; SameSite=Strict");
    }

    @Test
    @DisplayName("the issued token is hidden in its string form")
    void tokenIsHidden() {
        assertThat(new IssuedRefreshToken(TOKEN, Duration.ofDays(7)).toString()).doesNotContain(TOKEN);
    }
}
