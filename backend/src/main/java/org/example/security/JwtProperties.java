package org.example.security;

import java.time.Duration;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the access tokens (JWT, HS256).
 *
 * <p>The secret has no default, neither here nor in {@code application.yml}: a default would end up
 * in the repository, and anyone who knows it can forge a token of any user. Without a valid secret
 * the application does not start. The secret is set by the environment variable
 * {@code DROSO_JWT_SECRET}, which Spring binds to {@code droso.jwt.secret}.
 *
 * @param secret         the HMAC key in standard Base64; it must decode to at least 32 bytes (256
 *                       bits, the minimum of HS256 by RFC 7518). Generated with
 *                       {@code openssl rand -base64 32}
 * @param issuer         the claim {@code iss} of the issued tokens, required on verification;
 *                       {@code droso-backend} by default
 * @param accessTokenTtl how long an access token is valid; 15 minutes by default (a refresh token
 *                       gets the next one, {@code POST /api/v1/auth/refresh})
 */
@ConfigurationProperties(prefix = "droso.jwt")
public record JwtProperties(
        String secret,
        @DefaultValue("droso-backend") String issuer,
        @DefaultValue("15m") Duration accessTokenTtl) {

    /** 256 bits: the key of HS256 must not be shorter than the hash (RFC 7518, section 3.2). */
    static final int MIN_SECRET_BYTES = 32;

    /** The JCA name of the key; it pins nothing by itself, the algorithm is pinned in {@link JwtConfig}. */
    static final String KEY_ALGORITHM = "HmacSHA256";

    /**
     * Fails the start-up with a message that says what to do. The messages never contain the
     * secret, not even a part of it.
     *
     * @throws IllegalArgumentException if the secret is missing, not Base64 or too short, or if the
     *                                  issuer or the lifetime is invalid
     */
    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("The JWT secret is missing: set the environment variable "
                    + "DROSO_JWT_SECRET to at least " + MIN_SECRET_BYTES + " random bytes in Base64, "
                    + "e.g. the output of 'openssl rand -base64 32'");
        }
        if (decode(secret).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("The JWT secret (DROSO_JWT_SECRET) is too short: it must "
                    + "decode to at least " + MIN_SECRET_BYTES + " bytes");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("The JWT issuer (droso.jwt.issuer) must not be blank");
        }
        if (accessTokenTtl == null || accessTokenTtl.isNegative() || accessTokenTtl.isZero()) {
            throw new IllegalArgumentException("The lifetime of the access tokens (droso.jwt.access-token-ttl) "
                    + "must be positive");
        }
    }

    /** The HMAC key of the signature. */
    public SecretKey secretKey() {
        return new SecretKeySpec(decode(secret), KEY_ALGORITHM);
    }

    private static byte[] decode(String secret) {
        try {
            return Base64.getDecoder().decode(secret.strip());
        } catch (IllegalArgumentException e) {
            // Not chained: the message of the decoder may quote the offending character of the secret.
            throw new IllegalArgumentException("The JWT secret (DROSO_JWT_SECRET) is not valid Base64");
        }
    }

    /** Hides the secret: the generated {@code toString()} of a record would print it into any log. */
    @Override
    public String toString() {
        return "JwtProperties[secret=***, issuer=" + issuer + ", accessTokenTtl=" + accessTokenTtl + "]";
    }
}
