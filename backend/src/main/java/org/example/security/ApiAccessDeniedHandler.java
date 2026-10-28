package org.example.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.web.error.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Answers an authenticated request that is not allowed with {@code 403 FORBIDDEN} as an
 * {@link ApiError}. Like {@link ApiAuthenticationEntryPoint}, it runs outside Spring MVC.
 */
@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiAccessDeniedHandler.class);

    private final ApiErrorWriter writer;

    public ApiAccessDeniedHandler(ApiErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        log.debug("Request rejected with status 403: {}", e.getMessage());
        writer.write(response, ApiError.of(HttpStatus.FORBIDDEN));
    }
}
