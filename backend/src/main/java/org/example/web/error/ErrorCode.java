package org.example.web.error;

import org.springframework.http.HttpStatus;

/**
 * The error codes of the API that have a meaning of their own. Every other error of the web layer
 * gets the name of its HTTP status as its code (for example {@code METHOD_NOT_ALLOWED}).
 *
 * <p>The codes and the messages are part of the public contract: clients may rely on them, so they
 * must not change. The messages are fixed on purpose and never carry the text of an exception.
 */
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Request validation failed"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request body"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
