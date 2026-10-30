package org.example.user;

import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    /** The partial unique index on the email of the active users ({@code V3__create_users.sql}). */
    String EMAIL_ACTIVE_KEY = "users_email_active_key";

    /**
     * Whether an insert failed because an active user already has the email. The index decides,
     * not a query before the insert: of two concurrent inserts of one address, a query would let
     * both pass.
     */
    static boolean isEmailTaken(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation) {
                return EMAIL_ACTIVE_KEY.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }

    /**
     * The active user with this email, i.e. the one who may log in with it; there is at most one
     * (partial unique index {@code users_email_active_key}).
     *
     * @param email already {@linkplain EmailAddress#normalize normalized}
     */
    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    /** Whether an active user has this role, e.g. whether there is an administrator at all. */
    boolean existsByRoleAndDeletedAtIsNull(Role role);
}
