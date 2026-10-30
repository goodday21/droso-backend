package org.example.user.model;

import java.time.Instant;
import java.util.Set;

import org.example.user.Permission;
import org.example.user.Role;

/**
 * The user of the access token, as answered by {@code GET /api/v1/auth/me}: a {@link UserResponse}
 * plus what the user may do.
 *
 * <p>The permissions are given so that a client decides what to show (e.g. a button "Create") by
 * the answer of the server, instead of keeping its own copy of the matrix of {@link Role}, which
 * would drift from it. They are a hint for the interface only: the server checks them on every
 * request anyway.
 *
 * @param role        the current role in the database
 * @param permissions the permissions of that role, in the order of {@link Permission}
 */
public record CurrentUserResponse(Long id, String email, Role role, Set<Permission> permissions, Instant createdAt) {
}
