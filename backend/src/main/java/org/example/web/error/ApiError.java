package org.example.web.error;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Body of every error response of the API.
 *
 * <pre>{@code
 * {
 *   "status": 400,
 *   "code": "VALIDATION_ERROR",
 *   "message": "Request validation failed",
 *   "fieldErrors": { "title": "must not be blank" }
 * }
 * }</pre>
 *
 * @param status      the HTTP status, repeated in the body
 * @param code        stable, machine-readable code of the error
 * @param message     fixed, human-readable description of the code; never the text of an exception
 * @param fieldErrors the message per invalid field or parameter, sorted by name; always present,
 *                    empty unless the request failed validation
 */
public record ApiError(int status, String code, String message, Map<String, String> fieldErrors) {

    public ApiError {
        fieldErrors = fieldErrors == null
                ? Map.of()
                : Collections.unmodifiableSortedMap(new TreeMap<>(fieldErrors));
    }

    public static ApiError of(ErrorCode code) {
        return of(code, Map.of());
    }

    public static ApiError of(ErrorCode code, Map<String, String> fieldErrors) {
        return new ApiError(code.status().value(), code.name(), code.message(), fieldErrors);
    }

    /**
     * An error that has no code of its own: the name and the reason phrase of the status are used.
     */
    public static ApiError of(HttpStatusCode statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (status == null) {
            return new ApiError(statusCode.value(), "HTTP_" + statusCode.value(), "Request failed", Map.of());
        }
        return new ApiError(status.value(), status.name(), status.getReasonPhrase(), Map.of());
    }
}
