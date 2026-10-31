package org.example.auth;

/**
 * Thrown when a refresh fails. Answered with {@code 401 INVALID_REFRESH_TOKEN}, and the cookie is
 * deleted.
 *
 * <p>The cases (no cookie, unknown, expired, revoked, reused, user deleted) are not told apart: the
 * client does the same in each of them, it asks for the password again.
 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("The refresh token is missing, invalid, expired or revoked");
    }
}
