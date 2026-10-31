package org.example.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.example.security.AccessTokenService;
import org.example.security.AccessTokenService.AccessToken;
import org.example.user.User;
import org.example.user.UserRepository;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, rotates and revokes the refresh tokens.
 *
 * <p>A token is 32 random bytes in Base64url (43 characters); only its SHA-256 is stored. Every
 * refresh revokes the token it was given and issues the next one of the same family. A revoked token
 * presented again revokes its whole family: either the token was stolen and both the thief and the
 * user hold the chain, or two requests of one browser raced with one token; the server cannot tell
 * which, so the user has to log in again (a client serializes its refreshes to avoid it).
 *
 * <p>A refresh token is bound to the token version of its user, like an access token: incrementing
 * {@code users.token_version} (a deletion, {@link #logoutEverywhere}) revokes both.
 *
 * <p>A rejected refresh returns an empty result instead of throwing: the revocation of a family
 * must be committed although the request fails, and an exception would roll it back.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    /** 256 bits: guessing a token is out of reach, so the hash needs neither a salt nor slowness. */
    static final int TOKEN_BYTES = 32;

    /** The form of an issued token; anything else was not issued here and is not even looked up. */
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final RefreshTokenRepository repository;
    private final UserRepository users;
    private final AccessTokenService accessTokens;
    private final AuthProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository repository, UserRepository users,
            AccessTokenService accessTokens, AuthProperties properties, Clock clock) {
        this.repository = repository;
        this.users = users;
        this.accessTokens = accessTokens;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The first refresh token of a login: a new family, which ends after the maximum lifetime.
     *
     * @param user an active user, already stored
     */
    @Transactional
    public IssuedRefreshToken issue(User user) {
        Instant now = now();
        return store(user, UUID.randomUUID(), now.plus(properties.refreshTokenMaxLifetime()), now);
    }

    /**
     * Exchanges a refresh token for a new access token and the next refresh token of its family.
     *
     * @param value the token from the cookie, or {@code null} if there is none
     * @return empty if the token is missing, unknown, expired or revoked, or its user is deleted or
     *         has revoked the tokens since; a revoked token also revokes its family
     */
    @Transactional
    public Optional<SessionTokens> refresh(@Nullable String value) {
        Optional<RefreshToken> found = lock(value);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RefreshToken token = found.get();
        Instant now = now();
        if (token.isRevoked()) {
            if (repository.revokeFamily(token.getFamilyId(), now) > 0) {
                // Not the token or the hash: an id is enough to follow it up.
                log.warn("A revoked refresh token of user {} was presented again: its family {} is revoked",
                        token.getUserId(), token.getFamilyId());
            }
            return Optional.empty();
        }
        if (token.isExpired(now)) {
            return Optional.empty();
        }
        Optional<User> user = users.findById(token.getUserId())
                .filter(User::isActive)
                .filter(candidate -> candidate.getTokenVersion() == token.getTokenVersion());
        if (user.isEmpty()) {
            repository.revokeFamily(token.getFamilyId(), now);
            return Optional.empty();
        }
        token.revoke(now);
        IssuedRefreshToken next = store(user.get(), token.getFamilyId(), token.getFamilyExpiresAt(), now);
        return Optional.of(new SessionTokens(accessTokens.issue(user.get()), next));
    }

    /**
     * Ends the login of this token: revokes its family. Nothing happens if the token is missing,
     * unknown or revoked already, so that a logout always succeeds.
     *
     * @param value the token from the cookie, or {@code null} if there is none
     */
    @Transactional
    public void logout(@Nullable String value) {
        lock(value).ifPresent(token -> repository.revokeFamily(token.getFamilyId(), now()));
    }

    /**
     * Ends every login of the user: increments the token version, which revokes the access tokens
     * and the refresh tokens at once, and marks the refresh tokens revoked as well.
     *
     * <p>The row of the user is locked first, like by every change of a user
     * ({@code UserManagementService}): Hibernate writes every column, so an unlocked change could
     * overwrite a concurrent deletion.
     *
     * @param userId the id of the user
     */
    @Transactional
    public void logoutEverywhere(long userId) {
        users.lockById(userId).filter(User::isActive).ifPresent(User::revokeTokens);
        repository.revokeAllOfUser(userId, now());
    }

    private Optional<RefreshToken> lock(@Nullable String value) {
        if (value == null || !FORMAT.matcher(value).matches()) {
            return Optional.empty();
        }
        return repository.lockByTokenHash(hash(value));
    }

    private IssuedRefreshToken store(User user, UUID familyId, Instant familyExpiresAt, Instant now) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = min(now.plus(properties.refreshTokenTtl()), familyExpiresAt);
        repository.save(new RefreshToken(user.getId(), user.getTokenVersion(), hash(value), familyId,
                now, expiresAt, familyExpiresAt));
        return new IssuedRefreshToken(value, Duration.between(now, expiresAt));
    }

    static byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform must provide SHA-256.
            throw new IllegalStateException(e);
        }
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    /** Microseconds, as stored: the expiry of the cookie then matches the row exactly. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * A new refresh token, to be set as a cookie.
     *
     * @param value  the token; it leaves the server only in the cookie
     * @param maxAge how long it is valid, the {@code Max-Age} of the cookie
     */
    public record IssuedRefreshToken(String value, Duration maxAge) {

        /** Hides the token: whoever reads it in a log could log in as the user. */
        @Override
        public String toString() {
            return "IssuedRefreshToken[value=***, maxAge=" + maxAge + "]";
        }
    }

    /** The tokens of a login or a refresh. */
    public record SessionTokens(AccessToken accessToken, IssuedRefreshToken refreshToken) {
    }
}
