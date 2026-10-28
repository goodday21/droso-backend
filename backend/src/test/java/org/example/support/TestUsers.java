package org.example.support;

import java.util.concurrent.atomic.AtomicInteger;

import org.example.security.AccessTokenService;
import org.example.user.Role;
import org.example.user.User;
import org.example.user.UserRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Users and access tokens for the tests that call the protected API.
 *
 * <p>The users are stored directly, not registered: a registration hashes with the cost 12, a
 * quarter of a second per user. The tokens are real ones, issued by {@link AccessTokenService} and
 * signed with {@link #JWT_SECRET}, so a request goes through the whole check (signature, claims,
 * user in the database) instead of the shortcut {@code jwt()} of spring-security-test, which skips
 * the decoder and the converter.
 *
 * <p>Not a Spring bean on purpose: a component in the test sources would be picked up by the
 * component scan of every Spring Boot test.
 */
public final class TestUsers {

    /** The secret of the access tokens in every test context: 32 bytes, Base64. Never used outside the tests. */
    public static final String JWT_SECRET = "dGVzdC1zZWNyZXQtb2YtdGhlLWFjY2Vzcy10b2tlbnM=";

    /** The password of every user created here; it satisfies the password policy. */
    public static final String PASSWORD = "Sup3r-Secret!";

    /**
     * The hash of {@link #PASSWORD} with the minimal cost 4, in the format of the application's
     * encoder: it checks a hash with the cost written in the hash, so a login still works.
     */
    public static final String PASSWORD_HASH = "{bcrypt}" + new BCryptPasswordEncoder(4).encode(PASSWORD);

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private TestUsers() {
    }

    /** Stores an active user with a unique email and {@link #PASSWORD}. */
    public static User create(UserRepository users, Role role) {
        return create(users, "user" + COUNTER.incrementAndGet() + "@example.com", role);
    }

    /** Stores an active user with this email and {@link #PASSWORD}. */
    public static User create(UserRepository users, String email, Role role) {
        return users.saveAndFlush(new User(email, PASSWORD_HASH, role));
    }

    /** The value of the header {@code Authorization} with a fresh token of the user. */
    public static String bearer(AccessTokenService tokens, User user) {
        return "Bearer " + tokens.issue(user).value();
    }

    /** Adds the header {@code Authorization} to a MockMvc request. */
    public static RequestPostProcessor authorization(String bearer) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, bearer);
            return request;
        };
    }
}
