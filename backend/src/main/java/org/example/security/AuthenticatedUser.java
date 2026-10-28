package org.example.security;

import java.time.Instant;

import org.example.user.Role;
import org.example.user.User;

/**
 * The user of the current request, as loaded from the database when the access token was checked.
 * It is the principal of the {@link UserAuthentication}; a controller gets it with
 * {@code @AuthenticationPrincipal AuthenticatedUser user}.
 *
 * <p>A plain snapshot instead of the entity: the persistence context of the check is closed before
 * the controller runs ({@code open-in-view: false}).
 *
 * @param role the current role in the database, not the claim {@code role} of the token
 */
public record AuthenticatedUser(Long id, String email, Role role, Instant createdAt) {

    public static AuthenticatedUser of(User user) {
        return new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole(), user.getCreatedAt());
    }
}
