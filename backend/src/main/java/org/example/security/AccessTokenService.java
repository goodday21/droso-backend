package org.example.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.example.user.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues the access tokens.
 *
 * <p>A token is signed, not encrypted: anyone who holds it can read its claims, so it carries
 * nothing secret. The claims {@code email} and {@code role} are there for the client (to show who is
 * logged in); the server never trusts them and loads the user by {@code sub} on every request
 * ({@link UserJwtAuthenticationConverter}).
 */
@Service
public class AccessTokenService {

    /** The id of the user (as a string, RFC 7519); not the email, which may change and is personal data. */
    static final String SUBJECT = "sub";
    static final String EMAIL = "email";
    static final String ROLE = "role";
    /** The {@linkplain User#getTokenVersion() token version} of the user when the token was issued. */
    static final String VERSION = "ver";

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;

    public AccessTokenService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * An access token of the user.
     *
     * @param user an active user, already stored
     */
    public AccessToken issue(User user) {
        // Whole seconds: the claims of times are seconds (NumericDate), the rest would be lost anyway.
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Duration ttl = properties.accessTokenTtl();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(String.valueOf(user.getId()))
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(ttl))
                .id(UUID.randomUUID().toString())
                .claim(EMAIL, user.getEmail())
                .claim(ROLE, user.getRole().name())
                .claim(VERSION, user.getTokenVersion())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(value, ttl);
    }

    /**
     * An issued token and its lifetime.
     *
     * @param value the compact serialization of the JWT
     */
    public record AccessToken(String value, Duration expiresIn) {

        /** Hides the token: whoever reads it in a log could act as the user until it expires. */
        @Override
        public String toString() {
            return "AccessToken[value=***, expiresIn=" + expiresIn + "]";
        }
    }
}
