package org.example.security;

import java.util.List;
import java.util.Optional;

import org.example.user.Permission;
import org.example.user.Role;
import org.example.user.User;
import org.example.user.UserRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.transaction.TransactionException;

/**
 * Turns an access token whose signature and claims have been verified into the authentication of
 * its user, loaded from the database by the claim {@code sub}.
 *
 * <p>The token alone is not enough, because it is valid for its whole lifetime: the user is checked
 * on every request, so that a deleted user or a revoked token ({@code ver} lower than the
 * {@linkplain User#getTokenVersion() token version}) is rejected at once. The authorities come from
 * {@linkplain Role#permissions() permissions} of the current role in the database, one authority
 * per {@link Permission} named like it; the claim {@code role} is ignored. The role itself is not an
 * authority, so that no check can be written against a role instead of a permission.
 *
 * <p>A rejected token is an {@link InvalidBearerTokenException}: the filter answers it with
 * {@code 401}, like an invalid signature. The reason is not told to the client.
 *
 * <p>Created by {@link SecurityConfig}, not a component: {@code @WebMvcTest} picks up every
 * {@code Converter} bean, and this one would drag the repository into the slices.
 */
public class UserJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserRepository repository;

    public UserJwtAuthenticationConverter(UserRepository repository) {
        this.repository = repository;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        User user = load(userId(jwt))
                .filter(User::isActive)
                .orElseThrow(() -> new InvalidBearerTokenException("The user of the token does not exist or is deleted"));
        if (version(jwt) != user.getTokenVersion()) {
            throw new InvalidBearerTokenException("The token has been revoked");
        }
        List<SimpleGrantedAuthority> authorities = user.getRole().permissions().stream()
                .map(permission -> new SimpleGrantedAuthority(permission.name()))
                .toList();
        return new UserAuthentication(AuthenticatedUser.of(user), jwt, authorities);
    }

    /**
     * A failure of the database is turned into an {@link AuthenticationServiceException}, which
     * {@link ApiAuthenticationFailureHandler} answers with {@code 500}; any other exception would
     * leave the filter chain and reach the error page of Spring Boot. No transaction of its own: a
     * failure to open one would happen in a proxy, outside of this method.
     */
    private Optional<User> load(long id) {
        try {
            return repository.findById(id);
        } catch (DataAccessException | TransactionException e) {
            throw new AuthenticationServiceException("The user of the token could not be loaded", e);
        }
    }

    private static long userId(Jwt jwt) {
        String subject = jwt.getSubject();
        // Canonical decimal only, the way the tokens are issued: a token is signed, so anything else
        // was not issued by this server.
        if (subject == null || !subject.matches("[1-9][0-9]{0,18}")) {
            throw new InvalidBearerTokenException("The subject of the token is not a user id");
        }
        try {
            return Long.parseLong(subject);
        } catch (NumberFormatException e) {
            throw new InvalidBearerTokenException("The subject of the token is not a user id");
        }
    }

    private static long version(Jwt jwt) {
        if (!(jwt.getClaims().get(AccessTokenService.VERSION) instanceof Number version)) {
            throw new InvalidBearerTokenException("The token has no version");
        }
        return version.longValue();
    }
}
