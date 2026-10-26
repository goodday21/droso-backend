package org.example.web.error;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every exception of the web layer into an {@link ApiError}.
 *
 * <p>The standard exceptions of Spring MVC are mapped to their HTTP status by
 * {@link ResponseEntityExceptionHandler}; only the body is replaced here. Everything else is an
 * unexpected failure and answers {@code 500 INTERNAL_ERROR}.
 *
 * <p>No text of an exception ever reaches the client: the messages are fixed per code, and the
 * field errors carry the messages of the Bean Validation constraints only. The details are written
 * to the server log instead.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Replaces the messages of failed conversions, which name Java types and the rejected value. */
    static final String INVALID_VALUE = "has an invalid value";
    static final String REQUIRED = "is required";

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleResourceNotFound(ResourceNotFoundException e) {
        log.debug("Resource not found: {}", e.getMessage());
        return respond(ApiError.of(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        log.error("Unhandled exception while processing a request", e);
        return respond(ApiError.of(ErrorCode.INTERNAL_ERROR));
    }

    /**
     * Every standard exception of Spring MVC ends up here, with the status already chosen.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {

        if (statusCode.is5xxServerError()) {
            log.error("Request failed with status {}", statusCode.value(), ex);
        } else {
            log.debug("Request rejected with status {}: {}", statusCode.value(), ex.toString());
        }
        return super.handleExceptionInternal(ex, toApiError(ex, statusCode), headers, statusCode, request);
    }

    private static ApiError toApiError(Exception ex, HttpStatusCode statusCode) {
        if (ex instanceof MethodArgumentNotValidException e) {
            return ApiError.of(ErrorCode.VALIDATION_ERROR, fieldErrors(e.getBindingResult().getFieldErrors()));
        }
        if (ex instanceof HandlerMethodValidationException e) {
            return ApiError.of(ErrorCode.VALIDATION_ERROR, parameterErrors(e.getParameterValidationResults()));
        }
        if (ex instanceof TypeMismatchException e && statusCode.is4xxClientError()) {
            return ApiError.of(ErrorCode.VALIDATION_ERROR, single(e.getPropertyName(), INVALID_VALUE));
        }
        if (ex instanceof MissingServletRequestParameterException e) {
            return ApiError.of(ErrorCode.VALIDATION_ERROR, single(e.getParameterName(), REQUIRED));
        }
        if (ex instanceof HttpMessageNotReadableException) {
            return ApiError.of(ErrorCode.MALFORMED_REQUEST);
        }
        if (ex instanceof NoResourceFoundException || ex instanceof NoHandlerFoundException
                || statusCode.value() == ErrorCode.RESOURCE_NOT_FOUND.status().value()) {
            return ApiError.of(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (statusCode.value() == ErrorCode.INTERNAL_ERROR.status().value()) {
            return ApiError.of(ErrorCode.INTERNAL_ERROR);
        }
        return ApiError.of(statusCode);
    }

    private static Map<String, String> fieldErrors(List<FieldError> errors) {
        Map<String, List<String>> messages = new HashMap<>();
        for (FieldError error : errors) {
            // A failed conversion (a binding failure) has a technical message; a constraint does not.
            String message = error.isBindingFailure() || error.getDefaultMessage() == null
                    ? INVALID_VALUE
                    : error.getDefaultMessage();
            messages.computeIfAbsent(error.getField(), field -> new ArrayList<>()).add(message);
        }
        return join(messages);
    }

    private static Map<String, String> parameterErrors(List<ParameterValidationResult> results) {
        Map<String, List<String>> messages = new HashMap<>();
        for (ParameterValidationResult result : results) {
            if (result instanceof ParameterErrors errors) {
                // A validated object, e.g. a @Valid @RequestBody: report its fields.
                fieldErrors(errors.getFieldErrors()).forEach((field, message) ->
                        messages.computeIfAbsent(field, f -> new ArrayList<>()).add(message));
                continue;
            }
            String name = parameterName(result.getMethodParameter());
            result.getResolvableErrors().forEach(error -> messages
                    .computeIfAbsent(name, n -> new ArrayList<>())
                    .add(error.getDefaultMessage() == null ? INVALID_VALUE : error.getDefaultMessage()));
        }
        return join(messages);
    }

    /** The name the client used: the one of the annotation, otherwise the one of the parameter. */
    private static String parameterName(MethodParameter parameter) {
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam != null && !requestParam.name().isEmpty()) {
            return requestParam.name();
        }
        PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        if (pathVariable != null && !pathVariable.name().isEmpty()) {
            return pathVariable.name();
        }
        String name = parameter.getParameterName();
        return name != null ? name : "arg" + parameter.getParameterIndex();
    }

    /**
     * Several violated constraints of one field are joined in a fixed order, because the order in
     * which the validator reports them is not defined.
     */
    private static Map<String, String> join(Map<String, List<String>> messages) {
        Map<String, String> joined = new TreeMap<>();
        messages.forEach((field, list) -> joined.put(field, list.stream().sorted().distinct()
                .reduce((a, b) -> a + "; " + b).orElse(INVALID_VALUE)));
        return joined;
    }

    private static Map<String, String> single(@Nullable String name, String message) {
        return name == null ? Map.of() : Map.of(name, message);
    }

    private static ResponseEntity<ApiError> respond(ApiError error) {
        return ResponseEntity.status(error.status()).body(error);
    }
}
