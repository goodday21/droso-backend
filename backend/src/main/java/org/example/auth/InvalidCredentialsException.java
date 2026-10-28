package org.example.auth;

/**
 * Thrown when a login fails. Answered with {@code 401 INVALID_CREDENTIALS}.
 *
 * <p>The cases are not told apart, neither to the client nor in the message: an unknown email, a
 * deleted user and a wrong password look the same, so that a login cannot be used to find out
 * which addresses have an account.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
