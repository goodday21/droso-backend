package org.example.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A stored refresh token: the SHA-256 of the token, never the token itself. The table is created by
 * the migration {@code V7__create_refresh_tokens.sql}, which explains the columns.
 *
 * <p>A token is used once: a refresh {@linkplain #revoke revokes} it and issues the next token of
 * the same {@linkplain #getFamilyId() family}.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    /** The length of a SHA-256. */
    static final int HASH_BYTES = 32;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private byte[] tokenHash;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Column(name = "token_version", nullable = false, updatable = false)
    private int tokenVersion;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "family_expires_at", nullable = false, updatable = false)
    private Instant familyExpiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected RefreshToken() {
    }

    /**
     * A new, live token. The times are truncated to the microseconds PostgreSQL stores.
     *
     * @param userId          the id of the user
     * @param tokenVersion    the token version of the user now
     * @param tokenHash       the SHA-256 of the token, 32 bytes
     * @param familyId        the family: new at a login, the one of the previous token at a refresh
     * @param createdAt       now
     * @param expiresAt       the end of this token, after {@code createdAt} and not after
     *                        {@code familyExpiresAt}
     * @param familyExpiresAt the end of the family
     * @throws IllegalArgumentException if an argument is missing or the times are not in that order
     */
    public RefreshToken(Long userId, int tokenVersion, byte[] tokenHash, UUID familyId,
            Instant createdAt, Instant expiresAt, Instant familyExpiresAt) {
        if (userId == null || familyId == null || createdAt == null || expiresAt == null || familyExpiresAt == null) {
            throw new IllegalArgumentException("The user, the family and the times are required");
        }
        if (tokenHash == null || tokenHash.length != HASH_BYTES) {
            throw new IllegalArgumentException("The hash must have " + HASH_BYTES + " bytes");
        }
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        this.expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
        this.familyExpiresAt = familyExpiresAt.truncatedTo(ChronoUnit.MICROS);
        if (!this.createdAt.isBefore(this.expiresAt) || this.expiresAt.isAfter(this.familyExpiresAt)) {
            throw new IllegalArgumentException("The token must expire after its creation and not after its family");
        }
        this.userId = userId;
        this.tokenVersion = tokenVersion;
        this.tokenHash = tokenHash.clone();
        this.familyId = familyId;
    }

    /** Whether the token has expired at this moment; it expires exactly at {@link #getExpiresAt()}. */
    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** Revokes the token; a token revoked already keeps the moment of its first revocation. */
    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now.truncatedTo(ChronoUnit.MICROS);
        }
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    /** A copy of the stored hash; for the tests of this package, it never leaves the server. */
    byte[] getTokenHash() {
        return tokenHash.clone();
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getFamilyExpiresAt() {
        return familyExpiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
