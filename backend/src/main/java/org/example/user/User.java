package org.example.user;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * A user of the API. The table is created by the migration {@code V3__create_users.sql}.
 *
 * <p>The email is the login and is stored {@linkplain EmailAddress#normalize normalized}. The
 * password is never held here, only its hash. A user is soft-deleted: {@code deletedAt} is set and
 * the row stays.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, length = EmailAddress.MAX_LENGTH)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_code", nullable = false, length = 32)
    private Role role;

    /**
     * The version of the access tokens: a token is valid only while its claim {@code ver} equals
     * it, so incrementing it revokes every token issued before. Starts at 0 ({@code V5}).
     */
    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected User() {
    }

    /**
     * A new, active user.
     *
     * @param email        required; normalized and then checked against the rules of
     *                     {@link EmailAddress}
     * @param passwordHash required; the output of the password encoder, never the password
     * @param role         required
     * @throws IllegalArgumentException if the email is invalid or an argument is missing
     */
    public User(String email, String passwordHash, Role role) {
        String normalized = EmailAddress.normalize(email);
        if (!EmailAddress.isValid(normalized)) {
            // The address itself stays out of the message: it is personal data.
            throw new IllegalArgumentException("The email is not a valid address");
        }
        if (passwordHash == null || passwordHash.isEmpty()) {
            throw new IllegalArgumentException("The password hash is required");
        }
        if (role == null) {
            throw new IllegalArgumentException("The role is required");
        }
        this.email = normalized;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    /**
     * Sets both timestamps to the same moment, truncated to the microseconds PostgreSQL stores.
     */
    @PrePersist
    void initializeTimestamps() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Unlike a defect, a user is changed after its creation (password, role, deletion), so the
     * moment of the last change is kept up to date from the start.
     */
    @PreUpdate
    void refreshUpdatedAt() {
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Gives the user another role. Takes effect with the next request: the permissions are read
     * from the database on every request, so the tokens stay valid (their claim {@code role} is
     * then outdated, which only the client reads).
     *
     * @throws IllegalArgumentException if the role is missing
     * @throws IllegalStateException    if the user is deleted
     */
    public void changeRole(Role role) {
        if (role == null) {
            throw new IllegalArgumentException("The role is required");
        }
        requireActive();
        this.role = role;
    }

    /**
     * Soft-deletes the user and revokes every access token issued so far, so that the user is
     * logged out at once rather than when the tokens expire. The row stays, and the email becomes
     * free for a new registration.
     *
     * @param now the moment of the deletion
     * @throws IllegalStateException if the user is deleted already
     */
    public void delete(Instant now) {
        requireActive();
        this.deletedAt = now.truncatedTo(ChronoUnit.MICROS);
        this.tokenVersion++;
    }

    private void requireActive() {
        if (!isActive()) {
            throw new IllegalStateException("The user is deleted");
        }
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public Role getRole() {
        return role;
    }

    /** The stored hash, for the check of a login only; it never leaves the server. */
    public String getPasswordHash() {
        return passwordHash;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }

    /** Whether the user has not been deleted. */
    public boolean isActive() {
        return deletedAt == null;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
