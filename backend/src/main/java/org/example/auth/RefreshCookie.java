package org.example.auth;

import java.time.Duration;

import org.example.auth.RefreshTokenService.IssuedRefreshToken;
import org.springframework.http.ResponseCookie;

/**
 * The cookie that carries the refresh token.
 *
 * <ul>
 *   <li>{@code HttpOnly}: no script of the page can read it, so a cross-site scripting attack cannot
 *       steal the long-lived token (only use it while it runs);</li>
 *   <li>{@code Secure}: never sent over plain HTTP; browsers treat {@code http://localhost} as
 *       secure, so the development setup works without TLS;</li>
 *   <li>{@code SameSite=Strict}: never sent with a request started by another site, the first
 *       protection against cross-site request forgery (the second is the check of {@code Origin});</li>
 *   <li>{@code Path=/api/v1/auth}: sent to the endpoints of the authentication only, not with every
 *       request of the API.</li>
 * </ul>
 */
public final class RefreshCookie {

    public static final String NAME = "droso_refresh";
    static final String PATH = "/api/v1/auth";

    private RefreshCookie() {
    }

    /** The {@code Set-Cookie} value that stores the token for as long as it is valid. */
    public static String of(IssuedRefreshToken token) {
        return build(token.value(), token.maxAge());
    }

    /** The {@code Set-Cookie} value that deletes the cookie (logout, a rejected token). */
    public static String cleared() {
        return build("", Duration.ZERO);
    }

    private static String build(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(PATH)
                .maxAge(maxAge)
                .build()
                .toString();
    }
}
