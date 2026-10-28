package org.example.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.example.user.EmailAddress;
import org.example.user.PasswordPolicy;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * Request body of {@code POST /api/v1/auth/login}.
 *
 * <p>Only the presence and the length are validated, not the rules of a registration: a password
 * accepted by an older {@link PasswordPolicy} must still let its user in, and an address that does
 * not exist simply fails the login. The bounds keep an oversized input away from the password hash
 * (BCrypt reads 72 bytes at most); an answer {@code 400} for them tells nothing about the accounts.
 *
 * @param email    required; {@linkplain EmailAddress#normalize normalized}, like on the registration
 * @param password required; checked exactly as given, never normalized
 */
public record LoginRequest(
        @NotBlank @CodePointLength(max = EmailAddress.MAX_LENGTH) String email,
        @NotNull @CodePointLength(max = PasswordPolicy.MAX_LENGTH) String password) {

    public LoginRequest {
        email = EmailAddress.normalize(email);
    }

    /**
     * Hides the password: the generated {@code toString()} of a record would print it into any log
     * that ever shows the request.
     */
    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=***]";
    }
}
