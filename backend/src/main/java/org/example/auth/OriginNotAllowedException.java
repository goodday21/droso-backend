package org.example.auth;

/**
 * Thrown when a request that acts on the refresh cookie has no header {@code Origin} or one that is
 * not allowed ({@link OriginPolicy}). Answered with {@code 403 FORBIDDEN}.
 */
public class OriginNotAllowedException extends RuntimeException {

    public OriginNotAllowedException() {
        super("The origin of the request is not allowed");
    }
}
