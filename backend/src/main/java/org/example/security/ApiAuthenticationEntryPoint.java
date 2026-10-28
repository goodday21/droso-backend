package org.example.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.web.error.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Answers a request without a valid access token with {@code 401 UNAUTHORIZED} as an {@link ApiError}.
 *
 * <p>It runs in the filter chain, before Spring MVC, so {@code ApiExceptionHandler} never sees these
 * failures. The challenge says {@code Bearer} (RFC 6750); a token that was sent but rejected adds
 * {@code error="invalid_token"} so that the client knows to log in again. Unlike Spring's
 * {@code BearerTokenAuthenticationEntryPoint}, no {@code error_description} is sent: it would carry
 * the text of the exception, e.g. the moment the token expired.
 */
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(ApiAuthenticationEntryPoint.class);

    static final String CHALLENGE = "Bearer";
    static final String INVALID_TOKEN_CHALLENGE = "Bearer error=\"invalid_token\"";

    private final ApiErrorWriter writer;

    public ApiAuthenticationEntryPoint(ApiErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        log.debug("Request rejected with status 401: {}", e.getMessage());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                e instanceof InvalidBearerTokenException ? INVALID_TOKEN_CHALLENGE : CHALLENGE);
        writer.write(response, ApiError.of(HttpStatus.UNAUTHORIZED));
    }
}
