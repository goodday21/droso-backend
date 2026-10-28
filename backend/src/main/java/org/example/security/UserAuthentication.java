package org.example.security;

import java.util.Collection;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The authentication of a request that carried a valid access token: the principal is the
 * {@link AuthenticatedUser} loaded from the database, the credentials are the token.
 *
 * <p>Spring's {@code JwtAuthenticationToken} is not used because its principal is the token itself:
 * the controllers would read the user from the claims, which may be outdated (the role), instead of
 * from the database.
 */
public class UserAuthentication extends AbstractAuthenticationToken {

    private final AuthenticatedUser user;
    private final Jwt token;

    public UserAuthentication(AuthenticatedUser user, Jwt token, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.user = user;
        this.token = token;
        setAuthenticated(true);
    }

    @Override
    public AuthenticatedUser getPrincipal() {
        return user;
    }

    @Override
    public Jwt getCredentials() {
        return token;
    }

    /** The id of the user, as in the claim {@code sub}; never the email, which is personal data. */
    @Override
    public String getName() {
        return String.valueOf(user.id());
    }
}
