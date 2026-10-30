package org.example.user;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The role of a user. Every user has exactly one.
 *
 * <p>The codes are the rows of the table {@code roles} ({@code V3__create_users.sql}), to which
 * {@code users.role_code} refers: a new value needs a migration that inserts it.
 *
 * <p>The roles are nested: a manager may do everything a user may, an administrator everything a
 * manager may. Each role therefore lists its own permissions on top of those of the role below.
 */
public enum Role {

    /** Also manages the users. */
    ADMIN(Permission.DEFECT_READ, Permission.DEFECT_CREATE, Permission.DEFECT_UPDATE, Permission.USER_MANAGE),
    /** Also edits the defects. */
    MANAGER(Permission.DEFECT_READ, Permission.DEFECT_CREATE, Permission.DEFECT_UPDATE),
    /** The role of every user who registers on their own: reads and reports defects. */
    USER(Permission.DEFECT_READ, Permission.DEFECT_CREATE);

    private final Set<Permission> permissions;

    Role(Permission first, Permission... rest) {
        this.permissions = Collections.unmodifiableSet(EnumSet.of(first, rest));
    }

    /** What a user of this role may do; unmodifiable, in the order of {@link Permission}. */
    public Set<Permission> permissions() {
        return permissions;
    }
}
