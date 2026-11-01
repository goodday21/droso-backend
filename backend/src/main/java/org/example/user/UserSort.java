package org.example.user;

import org.springframework.data.domain.Sort;

/**
 * The fields by which the list of the users can be sorted ({@code GET /api/v1/users?sort=...}).
 *
 * <p>The email is compared byte by byte: the column has the collation {@code "C"}
 * ({@code V8__collate_users_email_c.sql}), and the addresses are lower-case ASCII, so this is the
 * plain alphabetical order on every server. The role is compared by its code
 * ({@code ADMIN < MANAGER < USER}), alphabetically too, not by its permissions.
 */
public enum UserSort {

    ID("id"),
    EMAIL("email"),
    ROLE("role");

    /** The property of {@link User}. */
    private final String property;

    UserSort(String property) {
        this.property = property;
    }

    /**
     * The order of a page. Many users share a role, so the id follows in the same direction: rows
     * with equal values would otherwise come in any order, and a user could appear on two pages or
     * on none.
     */
    public Sort toSort(Sort.Direction direction) {
        Sort sort = Sort.by(direction, property);
        return this == ID ? sort : sort.and(Sort.by(direction, ID.property));
    }
}
