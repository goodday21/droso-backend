package org.example.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * The active user with this email, i.e. the one who may log in with it; there is at most one
     * (partial unique index {@code users_email_active_key}).
     *
     * @param email already {@linkplain EmailAddress#normalize normalized}
     */
    Optional<User> findByEmailAndDeletedAtIsNull(String email);
}
