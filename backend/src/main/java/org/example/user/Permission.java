package org.example.user;

/**
 * What a user may do. The access checks name a permission, never a role
 * ({@code @PreAuthorize("hasAuthority('DEFECT_READ')")}): which role grants what is decided in one
 * place, {@link Role#permissions()}, and a new role does not need a change of every check.
 *
 * <p>The permissions live in the code only, not in the database: they are the vocabulary of the
 * checks, which a row of a table could not extend anyway.
 */
public enum Permission {

    DEFECT_READ,
    DEFECT_CREATE,
    /** Declared for the editing of the defects; no endpoint needs it yet. */
    DEFECT_UPDATE,
    /** The administration of the users (TASK-009). */
    USER_MANAGE
}
