package org.example.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.example.auth.model.LoginRequest;
import org.example.security.AccessTokenService;
import org.example.user.Role;
import org.example.user.User;
import org.example.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The parts of the login that a response does not show: which hash the password is checked against.
 */
class LoginServiceTest {

    private static final String DUMMY_HASH = "{bcrypt}dummy";
    private static final String USER_HASH = "{bcrypt}user";

    private final UserRepository repository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final AccessTokenService tokens = mock(AccessTokenService.class);
    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);

    private LoginService service;

    @BeforeEach
    void createService() {
        when(passwordEncoder.encode(anyString())).thenReturn(DUMMY_HASH);
        service = new LoginService(repository, passwordEncoder, tokens, refreshTokens);
    }

    @Test
    @DisplayName("an unknown email still checks the password, against the dummy hash, to take the same time")
    void unknownEmailChecksTheDummyHash() {
        when(repository.findByEmailAndDeletedAtIsNull("nobody@example.com")).thenReturn(Optional.empty());
        // Even a "match" against the dummy hash must not log anybody in.
        when(passwordEncoder.matches("Sup3r-Secret!", DUMMY_HASH)).thenReturn(true);

        assertThatThrownBy(() -> service.login(new LoginRequest("nobody@example.com", "Sup3r-Secret!")))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(passwordEncoder).matches("Sup3r-Secret!", DUMMY_HASH);
        verify(tokens, never()).issue(any());
        verify(refreshTokens, never()).issue(any());
    }

    @Test
    @DisplayName("a known email checks the password against the hash of the user")
    void knownEmailChecksTheUserHash() {
        User user = new User("qwe@example.com", USER_HASH, Role.USER);
        when(repository.findByEmailAndDeletedAtIsNull("qwe@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Wrong-Pa55word", USER_HASH)).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest("qwe@example.com", "Wrong-Pa55word")))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(passwordEncoder).matches(eq("Wrong-Pa55word"), eq(USER_HASH));
        verify(refreshTokens, never()).issue(any());
    }

    @Test
    @DisplayName("the dummy hash is computed once, when the service is created")
    void dummyHashOnce() {
        verify(passwordEncoder).encode(anyString());
    }
}
