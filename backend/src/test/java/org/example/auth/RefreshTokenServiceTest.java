package org.example.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.example.auth.RefreshTokenService.IssuedRefreshToken;
import org.example.auth.RefreshTokenService.SessionTokens;
import org.example.security.AccessTokenService;
import org.example.security.AccessTokenService.AccessToken;
import org.example.user.Role;
import org.example.user.User;
import org.example.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * The decisions of a refresh, with the repositories mocked. Their queries and locks against
 * PostgreSQL are checked by {@link RefreshTokenPersistenceIntegrationTest}.
 */
class RefreshTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");
    private static final Duration TTL = Duration.ofDays(7);
    private static final Duration MAX = Duration.ofDays(30);
    private static final long USER_ID = 7;

    private final RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AccessTokenService accessTokens = mock(AccessTokenService.class);
    private final AccessToken accessToken = new AccessToken("access", Duration.ofMinutes(15));
    private final User user = mock(User.class);

    private RefreshTokenService service;

    @BeforeEach
    void createService() {
        AuthProperties properties = new AuthProperties(true, TTL, MAX, List.of("http://localhost:4200"));
        service = new RefreshTokenService(repository, users, accessTokens, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        when(user.getId()).thenReturn(USER_ID);
        when(user.isActive()).thenReturn(true);
        when(user.getTokenVersion()).thenReturn(3);
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(accessTokens.issue(user)).thenReturn(accessToken);
    }

    private RefreshToken saved() {
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    /** A stored token of the user, found by the hash of {@code value}. */
    private RefreshToken stored(String value, int tokenVersion, Instant expiresAt, Instant familyExpiresAt) {
        RefreshToken token = new RefreshToken(USER_ID, tokenVersion, RefreshTokenService.hash(value), UUID.randomUUID(),
                NOW.minus(Duration.ofDays(1)), expiresAt, familyExpiresAt);
        when(repository.lockByTokenHash(any())).thenAnswer(invocation ->
                java.util.Arrays.equals(invocation.getArgument(0), RefreshTokenService.hash(value))
                        ? Optional.of(token) : Optional.empty());
        return token;
    }

    private RefreshToken stored(String value) {
        return stored(value, 3, NOW.plus(Duration.ofDays(6)), NOW.plus(Duration.ofDays(29)));
    }

    private static String value(char c) {
        return String.valueOf(c).repeat(43);
    }

    // ---------------------------------------------------------------- issue

    @Test
    @DisplayName("a login issues 256 random bits as 43 Base64url characters and stores only their SHA-256")
    void issue() {
        IssuedRefreshToken issued = service.issue(user);

        assertThat(issued.value()).matches("[A-Za-z0-9_-]{43}");
        assertThat(issued.maxAge()).isEqualTo(TTL);
        RefreshToken token = saved();
        assertThat(token.getTokenHash()).isEqualTo(RefreshTokenService.hash(issued.value()))
                .isNotEqualTo(issued.value().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThat(token.getUserId()).isEqualTo(USER_ID);
        assertThat(token.getTokenVersion()).isEqualTo(3);
        assertThat(token.getCreatedAt()).isEqualTo(NOW);
        assertThat(token.getExpiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(token.getFamilyExpiresAt()).isEqualTo(NOW.plus(MAX));
        assertThat(token.isRevoked()).isFalse();
    }

    @Test
    @DisplayName("every login starts a new family with a new token")
    void issueTwice() {
        IssuedRefreshToken first = service.issue(user);
        IssuedRefreshToken second = service.issue(user);

        assertThat(first.value()).isNotEqualTo(second.value());
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getFamilyId()).isNotEqualTo(captor.getAllValues().get(1).getFamilyId());
    }

    // ---------------------------------------------------------------- refresh

    @Test
    @DisplayName("a refresh revokes the token and issues the next one of the same family, with an access token")
    void rotate() {
        RefreshToken old = stored(value('a'));

        Optional<SessionTokens> tokens = service.refresh(value('a'));

        assertThat(tokens).isPresent();
        assertThat(tokens.get().accessToken()).isSameAs(accessToken);
        assertThat(tokens.get().refreshToken().value()).isNotEqualTo(value('a'));
        assertThat(old.isRevoked()).isTrue();
        assertThat(old.getRevokedAt()).isEqualTo(NOW);
        RefreshToken next = saved();
        assertThat(next.getFamilyId()).isEqualTo(old.getFamilyId());
        assertThat(next.getFamilyExpiresAt()).isEqualTo(old.getFamilyExpiresAt());
        assertThat(next.getExpiresAt()).isEqualTo(NOW.plus(TTL));
        verify(repository, never()).revokeFamily(any(), any());
    }

    @Test
    @DisplayName("near the end of the family the next token expires with the family, and so does the cookie")
    void rotateNearTheEndOfTheFamily() {
        stored(value('a'), 3, NOW.plus(Duration.ofDays(2)), NOW.plus(Duration.ofDays(2)));

        IssuedRefreshToken next = service.refresh(value('a')).orElseThrow().refreshToken();

        assertThat(next.maxAge()).isEqualTo(Duration.ofDays(2));
        assertThat(saved().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(2)));
    }

    @Test
    @DisplayName("a revoked token presented again revokes its family and fails")
    void reuse() {
        RefreshToken old = stored(value('a'));
        old.revoke(NOW.minusSeconds(10));
        when(repository.revokeFamily(old.getFamilyId(), NOW)).thenReturn(1);

        assertThat(service.refresh(value('a'))).isEmpty();

        verify(repository).revokeFamily(old.getFamilyId(), NOW);
        verify(repository, never()).save(any());
        verifyNoInteractions(accessTokens);
    }

    @Test
    @DisplayName("an expired token fails without revoking anything: expiry is no sign of theft")
    void expired() {
        RefreshToken old = stored(value('a'), 3, NOW, NOW.plus(Duration.ofDays(20)));

        assertThat(service.refresh(value('a'))).isEmpty();

        assertThat(old.isRevoked()).isFalse();
        verify(repository, never()).revokeFamily(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a token of a deleted user fails and its family is revoked")
    void deletedUser() {
        RefreshToken old = stored(value('a'));
        when(user.isActive()).thenReturn(false);

        assertThat(service.refresh(value('a'))).isEmpty();

        verify(repository).revokeFamily(old.getFamilyId(), NOW);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a token issued before the user revoked the tokens (another token version) fails")
    void staleTokenVersion() {
        RefreshToken old = stored(value('a'), 2, NOW.plus(Duration.ofDays(6)), NOW.plus(Duration.ofDays(29)));

        assertThat(service.refresh(value('a'))).isEmpty();

        verify(repository).revokeFamily(old.getFamilyId(), NOW);
        verifyNoInteractions(accessTokens);
    }

    @Test
    @DisplayName("an unknown token fails without changing anything")
    void unknown() {
        stored(value('a'));

        assertThat(service.refresh(value('b'))).isEmpty();

        verify(repository, never()).revokeFamily(any(), any());
        verify(repository, never()).save(any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "short", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa+",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa="})
    @DisplayName("a value that was not issued here (missing, wrong length, standard Base64) is not even looked up")
    void malformed(String value) {
        assertThat(service.refresh(value)).isEmpty();

        verifyNoInteractions(repository);
    }

    // ---------------------------------------------------------------- logout

    @Test
    @DisplayName("a logout revokes the family of the token, whatever its state")
    void logout() {
        RefreshToken old = stored(value('a'), 3, NOW, NOW.plus(Duration.ofDays(20)));

        service.logout(value('a'));

        verify(repository).revokeFamily(old.getFamilyId(), NOW);
    }

    @Test
    @DisplayName("a logout without a known token does nothing")
    void logoutUnknown() {
        stored(value('a'));

        service.logout(value('b'));
        service.logout(null);

        verify(repository, never()).revokeFamily(any(), any());
    }

    @Test
    @DisplayName("a logout everywhere increments the token version of the locked user and revokes the refresh tokens")
    void logoutEverywhere() {
        User active = new User("a@b.cd", "{bcrypt}hash", Role.USER);
        when(users.lockById(USER_ID)).thenReturn(Optional.of(active));

        service.logoutEverywhere(USER_ID);

        assertThat(active.getTokenVersion()).isEqualTo(1);
        verify(repository).revokeAllOfUser(USER_ID, NOW);
    }
}
