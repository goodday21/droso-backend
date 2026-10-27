package org.example.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.example.user.EmailAddress;
import org.example.user.PasswordPolicy;
import org.example.user.validation.ValidEmail;
import org.example.user.validation.ValidPassword;

/**
 * Request body of {@code POST /api/v1/auth/register}.
 *
 * <p>The client chooses the email and the password only: the role of a new user is always
 * {@code USER}. Since unknown JSON properties are rejected, a request that sends a {@code role}
 * answers {@code 400} instead of being silently ignored.
 *
 * @param email    required; {@linkplain EmailAddress#normalize normalized} before it is validated,
 *                 so that the rules apply to the address as it will be stored
 * @param password required; validated by {@link PasswordPolicy} exactly as given, never normalized
 */
public record RegisterRequest(
        @NotBlank @ValidEmail String email,
        @NotNull @ValidPassword String password) {

    public RegisterRequest {
        email = EmailAddress.normalize(email);
    }

    /**
     * Hides the password: the generated {@code toString()} of a record would print it into any log
     * that ever shows the request.
     */
    @Override
    public String toString() {
        return "RegisterRequest[email=" + email + ", password=***]";
    }
}
