package org.example.user;

/**
 * The role of a user. Every user has exactly one.
 *
 * <p>The codes are the rows of the table {@code roles} ({@code V3__create_users.sql}), to which
 * {@code users.role_code} refers: a new value needs a migration that inserts it.
 */
public enum Role {

    ADMIN,
    MANAGER,
    /** The role of every user who registers on their own. */
    USER
}
