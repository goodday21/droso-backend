package org.example.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.example.auth.RefreshTokenService.IssuedRefreshToken;
import org.example.auth.RefreshTokenService.SessionTokens;
import org.example.support.PostgresIntegrationTest;
import org.example.support.TestUsers;
import org.example.user.Role;
import org.example.user.User;
import org.example.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The table {@code refresh_tokens} ({@code V7}) and the queries of {@link RefreshTokenRepository}
 * against PostgreSQL: the mapping, the constraints, the bulk revocations, and the row lock that
 * makes two refreshes with one token a detected reuse instead of two new tokens.
 */
// A lock that is never released must fail the test, not hang the build.
@Timeout(30)
@SpringBootTest
class RefreshTokenPersistenceIntegrationTest extends PostgresIntegrationTest {

    /** Long enough for the second refresh to reach the lock on any machine of the CI. */
    private static final long WAIT_MILLIS = 500;

    @Autowired
    private RefreshTokenRepository repository;

    @Autowired
    private RefreshTokenService service;

    @Autowired
    private UserRepository users;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    private User user;

    @BeforeEach
    void storeUser() {
        user = TestUsers.create(users, Role.USER);
    }

    private static byte[] hash(char c) {
        return RefreshTokenService.hash(String.valueOf(c).repeat(43));
    }

    private RefreshToken token(long userId, byte[] hash, UUID family) {
        Instant now = Instant.now();
        return new RefreshToken(userId, 0, hash, family, now, now.plus(Duration.ofDays(7)), now.plus(Duration.ofDays(30)));
    }

    private int insert(Object hash, String createdAt, String expiresAt, String familyExpiresAt) {
        return jdbc.update("INSERT INTO refresh_tokens (user_id, token_hash, family_id, token_version, expires_at, "
                        + "family_expires_at, created_at) VALUES (?, ?, gen_random_uuid(), 0, ?::timestamptz, ?::timestamptz, ?::timestamptz)",
                user.getId(), hash, expiresAt, familyExpiresAt, createdAt);
    }

    private long liveTokens() {
        return jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL", Long.class);
    }

    // ---------------------------------------------------------------- mapping and constraints

    @Test
    @DisplayName("a token is stored and found again by its hash, every column mapped (bytea, uuid, timestamptz)")
    void roundTrip() {
        UUID family = UUID.randomUUID();
        RefreshToken stored = repository.saveAndFlush(token(user.getId(), hash('a'), family));

        RefreshToken found = transaction.execute(status -> repository.lockByTokenHash(hash('a')).orElseThrow());

        assertThat(found.getId()).isEqualTo(stored.getId());
        assertThat(found.getUserId()).isEqualTo(user.getId());
        assertThat(found.getTokenHash()).isEqualTo(hash('a'));
        assertThat(found.getFamilyId()).isEqualTo(family);
        assertThat(found.getExpiresAt()).isEqualTo(stored.getExpiresAt());
        assertThat(found.getFamilyExpiresAt()).isEqualTo(stored.getFamilyExpiresAt());
        assertThat(found.getCreatedAt()).isEqualTo(stored.getCreatedAt());
        assertThat(found.getRevokedAt()).isNull();
        Optional<RefreshToken> unknown = transaction.execute(status -> repository.lockByTokenHash(hash('b')));
        assertThat(unknown).isEmpty();
    }

    @Test
    @DisplayName("the hash has exactly 32 bytes and is unique")
    void hashConstraints() {
        assertThatThrownBy(() -> insert(new byte[31], "2026-10-31T10:00:00Z", "2026-11-07T10:00:00Z", "2026-11-30T10:00:00Z"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("refresh_tokens_token_hash_length_check");
        insert(hash('a'), "2026-10-31T10:00:00Z", "2026-11-07T10:00:00Z", "2026-11-30T10:00:00Z");
        assertThatThrownBy(() -> insert(hash('a'), "2026-10-31T10:00:00Z", "2026-11-07T10:00:00Z", "2026-11-30T10:00:00Z"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("refresh_tokens_token_hash_key");
    }

    @Test
    @DisplayName("a token expires after its creation and not after its family")
    void expiryConstraint() {
        assertThatThrownBy(() -> insert(hash('a'), "2026-10-31T10:00:00Z", "2026-10-31T10:00:00Z", "2026-11-30T10:00:00Z"))
                .hasMessageContaining("refresh_tokens_expiry_check");
        assertThatThrownBy(() -> insert(hash('b'), "2026-10-31T10:00:00Z", "2026-12-01T10:00:00Z", "2026-11-30T10:00:00Z"))
                .hasMessageContaining("refresh_tokens_expiry_check");
    }

    @Test
    @DisplayName("a token belongs to an existing user")
    void userForeignKey() {
        assertThatThrownBy(() -> repository.saveAndFlush(token(user.getId() + 1000, hash('a'), UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("refresh_tokens_user_id_fkey");
    }

    // ---------------------------------------------------------------- bulk revocations

    @Test
    @DisplayName("revoking a family revokes its live tokens only, and counts them")
    void revokeFamily() {
        UUID family = UUID.randomUUID();
        repository.saveAndFlush(token(user.getId(), hash('a'), family));
        repository.saveAndFlush(token(user.getId(), hash('b'), family));
        repository.saveAndFlush(token(user.getId(), hash('c'), UUID.randomUUID()));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        Integer revoked = transaction.execute(status -> repository.revokeFamily(family, now));
        Integer revokedAgain = transaction.execute(status -> repository.revokeFamily(family, now.plusSeconds(1)));

        assertThat(revoked).isEqualTo(2);
        assertThat(revokedAgain).isZero();

        assertThat(liveTokens()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT DISTINCT revoked_at FROM refresh_tokens WHERE family_id = ?", family))
                .hasSize(1);
    }

    @Test
    @DisplayName("revoking the tokens of a user leaves those of the other users")
    void revokeAllOfUser() {
        User other = TestUsers.create(users, Role.USER);
        repository.saveAndFlush(token(user.getId(), hash('a'), UUID.randomUUID()));
        repository.saveAndFlush(token(user.getId(), hash('b'), UUID.randomUUID()));
        repository.saveAndFlush(token(other.getId(), hash('c'), UUID.randomUUID()));

        Integer revoked = transaction.execute(status -> repository.revokeAllOfUser(user.getId(), Instant.now()));

        assertThat(revoked).isEqualTo(2);

        assertThat(jdbc.queryForObject("SELECT user_id FROM refresh_tokens WHERE revoked_at IS NULL", Long.class))
                .isEqualTo(other.getId());
    }

    // ---------------------------------------------------------------- the service against the database

    @Test
    @DisplayName("a refresh revokes the old row and stores the next one; reusing the old one revokes the new one too")
    void rotationAndReuse() {
        IssuedRefreshToken first = service.issue(user);
        IssuedRefreshToken second = service.refresh(first.value()).orElseThrow().refreshToken();

        assertThat(liveTokens()).isEqualTo(1);
        assertThat(service.refresh(first.value())).isEmpty();
        assertThat(liveTokens()).isZero();
        assertThat(service.refresh(second.value())).isEmpty();
    }

    @Test
    @DisplayName("a logout everywhere increments the token version, so even a token missed by the revocation fails")
    void logoutEverywhere() {
        IssuedRefreshToken issued = service.issue(user);

        service.logoutEverywhere(user.getId());

        assertThat(users.findById(user.getId()).orElseThrow().getTokenVersion()).isEqualTo(1);
        assertThat(liveTokens()).isZero();
        // Even if the row were live again, the version of the token is stale.
        jdbc.update("UPDATE refresh_tokens SET revoked_at = NULL");
        assertThat(service.refresh(issued.value())).isEmpty();
    }

    @Test
    @DisplayName("two refreshes with one token at once: the second waits for the first, then counts as a reuse")
    void concurrentRefresh() throws Exception {
        String value = service.issue(user).value();
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // The first refresh runs in a transaction of the test that stays open, its row lock held.
        CompletableFuture<Optional<SessionTokens>> first = CompletableFuture.supplyAsync(
                () -> transaction.execute(status -> {
                    Optional<SessionTokens> tokens = service.refresh(value);
                    firstDone.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return tokens;
                }));
        CompletableFuture<Optional<SessionTokens>> second;
        try {
            assertThat(firstDone.await(10, TimeUnit.SECONDS)).as("the first refresh is done").isTrue();
            second = CompletableFuture.supplyAsync(() -> service.refresh(value));
            Thread.sleep(WAIT_MILLIS);
            assertThat(second).as("the second refresh waits for the lock of the first").isNotDone();
        } finally {
            // Released in any case: an open transaction would keep its lock, and the truncation
            // before the next test would wait for it forever.
            release.countDown();
        }

        assertThat(first.get(10, TimeUnit.SECONDS)).isPresent();
        assertThat(second.get(10, TimeUnit.SECONDS)).isEmpty();
        // The token issued to the first refresh is revoked as well: the family is gone.
        assertThat(liveTokens()).isZero();
    }
}
