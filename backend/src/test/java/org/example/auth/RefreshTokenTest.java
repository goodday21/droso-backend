package org.example.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefreshTokenTest {

    private static final Instant NOW = Instant.parse("2026-10-31T10:00:00.123456789Z");
    private static final Instant EXPIRES = NOW.plus(Duration.ofDays(7));
    private static final Instant FAMILY_EXPIRES = NOW.plus(Duration.ofDays(30));
    private static final UUID FAMILY = UUID.randomUUID();

    private static RefreshToken token(byte[] hash, Instant createdAt, Instant expiresAt, Instant familyExpiresAt) {
        return new RefreshToken(1L, 0, hash, FAMILY, createdAt, expiresAt, familyExpiresAt);
    }

    private static RefreshToken token() {
        return token(new byte[32], NOW, EXPIRES, FAMILY_EXPIRES);
    }

    @Test
    @DisplayName("a new token is live, keeps a copy of the hash and stores the times in microseconds")
    void newToken() {
        byte[] hash = new byte[32];
        RefreshToken token = token(hash, NOW, EXPIRES, FAMILY_EXPIRES);
        hash[0] = 1;

        assertThat(token.isRevoked()).isFalse();
        assertThat(token.getRevokedAt()).isNull();
        assertThat(token.getFamilyId()).isEqualTo(FAMILY);
        assertThat(token.getCreatedAt()).isEqualTo(Instant.parse("2026-10-31T10:00:00.123456Z"));
        assertThat(token.getExpiresAt().getNano() % 1000).isZero();
        assertThat(token.getFamilyExpiresAt().getNano() % 1000).isZero();
    }

    @Test
    @DisplayName("a token expires exactly at its expiry, not a microsecond later")
    void expiry() {
        RefreshToken token = token();
        Instant expiresAt = token.getExpiresAt();

        assertThat(token.isExpired(expiresAt.minusNanos(1000))).isFalse();
        assertThat(token.isExpired(expiresAt)).isTrue();
        assertThat(token.isExpired(expiresAt.plusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("a revocation keeps the moment of the first one")
    void revokeOnce() {
        RefreshToken token = token();

        token.revoke(NOW.plusSeconds(1));
        token.revoke(NOW.plusSeconds(2));

        assertThat(token.isRevoked()).isTrue();
        assertThat(token.getRevokedAt()).isEqualTo(NOW.plusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    @Test
    @DisplayName("the hash must have 32 bytes")
    void hashLength() {
        assertThatIllegalArgumentException().isThrownBy(() -> token(null, NOW, EXPIRES, FAMILY_EXPIRES));
        assertThatIllegalArgumentException().isThrownBy(() -> token(new byte[31], NOW, EXPIRES, FAMILY_EXPIRES));
        assertThatIllegalArgumentException().isThrownBy(() -> token(new byte[33], NOW, EXPIRES, FAMILY_EXPIRES));
    }

    @Test
    @DisplayName("a token expires after its creation and not after its family")
    void timeOrder() {
        assertThatIllegalArgumentException().isThrownBy(() -> token(new byte[32], NOW, NOW, FAMILY_EXPIRES));
        assertThatIllegalArgumentException().isThrownBy(() -> token(new byte[32], NOW, NOW.minusSeconds(1), FAMILY_EXPIRES));
        assertThatIllegalArgumentException().isThrownBy(() -> token(new byte[32], NOW, FAMILY_EXPIRES.plusSeconds(1), FAMILY_EXPIRES));
        // Expiring together with the family is allowed: the last token of a family does.
        assertThat(token(new byte[32], NOW, FAMILY_EXPIRES, FAMILY_EXPIRES).getExpiresAt())
                .isEqualTo(token().getFamilyExpiresAt());
    }

    @Test
    @DisplayName("the user, the family and the times are required")
    void required() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new RefreshToken(null, 0, new byte[32], FAMILY, NOW, EXPIRES, FAMILY_EXPIRES));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new RefreshToken(1L, 0, new byte[32], null, NOW, EXPIRES, FAMILY_EXPIRES));
        assertThatIllegalArgumentException().isThrownBy(() -> token(new byte[32], null, EXPIRES, FAMILY_EXPIRES));
        assertThatIllegalArgumentException().isThrownBy(() -> token(new byte[32], NOW, null, FAMILY_EXPIRES));
        assertThatIllegalArgumentException().isThrownBy(() -> token(new byte[32], NOW, EXPIRES, null));
    }
}
