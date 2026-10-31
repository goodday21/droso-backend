package org.example.auth;

import java.util.Optional;
import java.util.UUID;

import org.example.auth.RefreshTokenService.SessionTokens;
import org.example.auth.model.LoginRequest;
import org.example.security.AccessTokenService;
import org.example.user.User;
import org.example.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The login with the email and the password.
 */
@Service
public class LoginService {

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService tokens;
    private final RefreshTokenService refreshTokens;

    /**
     * The hash of a random password nobody knows, checked when the email has no active user. Without
     * it, such a login would answer at once while a real one takes the time of BCrypt (about a
     * quarter of a second), and the difference would tell which addresses have an account.
     */
    private final String dummyHash;

    public LoginService(UserRepository repository, PasswordEncoder passwordEncoder, AccessTokenService tokens,
            RefreshTokenService refreshTokens) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Issues an access token and the first refresh token of a new family if the email belongs to an
     * active user and the password matches.
     *
     * @throws InvalidCredentialsException otherwise, the same for every reason
     */
    @Transactional
    public SessionTokens login(LoginRequest request) {
        Optional<User> user = repository.findByEmailAndDeletedAtIsNull(request.email());
        // The password is checked in either case, so that both take the same time.
        boolean matches = passwordEncoder.matches(request.password(), user.map(User::getPasswordHash).orElse(dummyHash));
        if (user.isEmpty() || !matches) {
            throw new InvalidCredentialsException();
        }
        return new SessionTokens(tokens.issue(user.get()), refreshTokens.issue(user.get()));
    }
}
