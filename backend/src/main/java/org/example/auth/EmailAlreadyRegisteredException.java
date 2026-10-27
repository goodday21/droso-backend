package org.example.auth;

/**
 * Thrown when an active user already has the email of a registration. Answered with
 * {@code 409 EMAIL_ALREADY_REGISTERED}.
 *
 * <p>The message does not contain the address: the log is no place for personal data.
 */
public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException(Throwable cause) {
        super("An active user already has this email", cause);
    }
}
