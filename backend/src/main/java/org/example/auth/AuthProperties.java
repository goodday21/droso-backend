package org.example.auth;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the registration, the refresh tokens and the browsers allowed to use them.
 *
 * @param registrationEnabled      whether anyone may register with {@code POST /api/v1/auth/register};
 *                                 {@code true} by default. A deployment that wants no
 *                                 self-registered users turns it off
 *                                 ({@code DROSO_AUTH_REGISTRATION_ENABLED=false})
 * @param refreshTokenTtl          how long a refresh token is valid after its issue; every refresh
 *                                 issues a new one, so a user who comes back within this time stays
 *                                 logged in. 7 days by default
 * @param refreshTokenMaxLifetime  how long the refresh tokens of one login are valid at most, however
 *                                 often they are refreshed; then the password is required again. 30
 *                                 days by default, not shorter than {@code refreshTokenTtl}
 * @param allowedOrigins           the origins ({@code scheme://host[:port]}) of the web pages that may
 *                                 call the API from a browser: the only ones whose requests may
 *                                 refresh or log out with the cookie, and the CORS allowlist.
 *                                 {@code DROSO_AUTH_ALLOWED_ORIGINS}, comma-separated;
 *                                 {@code http://localhost:4200} (the development server of
 *                                 droso-frontend) by default
 */
@ConfigurationProperties(prefix = "droso.auth")
public record AuthProperties(
        @DefaultValue("true") boolean registrationEnabled,
        @DefaultValue("7d") Duration refreshTokenTtl,
        @DefaultValue("30d") Duration refreshTokenMaxLifetime,
        @DefaultValue("http://localhost:4200") List<String> allowedOrigins) {

    /**
     * Fails the start-up with a message that says what is wrong, and normalizes the origins to
     * lower case (the form in which a browser sends the header {@code Origin}).
     *
     * @throws IllegalArgumentException if a lifetime is not positive, the maximum is shorter than the
     *                                  lifetime of one token, or an origin is missing or invalid
     */
    public AuthProperties {
        if (refreshTokenTtl == null || refreshTokenTtl.isNegative() || refreshTokenTtl.isZero()) {
            throw new IllegalArgumentException("The lifetime of the refresh tokens (droso.auth.refresh-token-ttl) "
                    + "must be positive");
        }
        if (refreshTokenMaxLifetime == null || refreshTokenMaxLifetime.compareTo(refreshTokenTtl) < 0) {
            throw new IllegalArgumentException("The maximum lifetime of the refresh tokens "
                    + "(droso.auth.refresh-token-max-lifetime) must not be shorter than droso.auth.refresh-token-ttl");
        }
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException("At least one allowed origin is required "
                    + "(droso.auth.allowed-origins, DROSO_AUTH_ALLOWED_ORIGINS)");
        }
        allowedOrigins = allowedOrigins.stream().map(AuthProperties::origin).toList();
    }

    /**
     * An origin exactly as a browser sends it: a scheme {@code http} or {@code https}, a host, an
     * optional port, and nothing else (no path, not even a trailing slash).
     */
    static String origin(String value) {
        String origin = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        try {
            URI uri = new URI(origin);
            boolean valid = ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && uri.getHost() != null
                    && uri.getRawUserInfo() == null
                    && uri.getRawPath().isEmpty()
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
            if (valid) {
                return origin;
            }
        } catch (URISyntaxException e) {
            // Answered below, like any other invalid value.
        }
        throw new IllegalArgumentException("Invalid allowed origin '" + value
                + "' (droso.auth.allowed-origins): expected scheme://host[:port], e.g. https://droso.example.com");
    }
}
