package org.example.web.error;

/**
 * Thrown when a requested resource does not exist. Answered with {@code 404 RESOURCE_NOT_FOUND}.
 *
 * <p>The message names the resource and its id for the server log only; the client gets the fixed
 * message of {@link ErrorCode#RESOURCE_NOT_FOUND}.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resource, Object id) {
        super(resource + " with id " + id + " not found");
    }
}
