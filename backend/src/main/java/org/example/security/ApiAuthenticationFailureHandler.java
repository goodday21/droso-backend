package org.example.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.web.error.ApiError;
import org.example.web.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * Answers a failed check of an access token.
 *
 * <p>A rejected token is a {@code 401} of the {@link ApiAuthenticationEntryPoint}. A token that could
 * not be checked at all, because the database is down ({@link AuthenticationServiceException}), is a
 * failure of the server: {@code 500 INTERNAL_ERROR}, like the same failure inside a controller, and
 * not a {@code 401} that would send the client to log in again. Spring's default handler rethrows it
 * instead, and the error page of Spring Boot would answer without an {@code ApiError}.
 */
@Component
public class ApiAuthenticationFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiAuthenticationFailureHandler.class);

    private final ApiAuthenticationEntryPoint entryPoint;
    private final ApiErrorWriter writer;

    public ApiAuthenticationFailureHandler(ApiAuthenticationEntryPoint entryPoint, ApiErrorWriter writer) {
        this.entryPoint = entryPoint;
        this.writer = writer;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException e) throws IOException {
        if (e instanceof AuthenticationServiceException) {
            log.error("The access token could not be checked", e);
            writer.write(response, ApiError.of(ErrorCode.INTERNAL_ERROR));
            return;
        }
        entryPoint.commence(request, response, e);
    }
}
