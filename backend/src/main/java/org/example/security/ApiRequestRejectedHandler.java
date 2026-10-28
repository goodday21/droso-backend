package org.example.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.web.error.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.stereotype.Component;

/**
 * Answers a URL that the firewall of Spring Security rejects ({@code StrictHttpFirewall}: a
 * {@code ;}, an encoded {@code %3B}, {@code %25}, {@code //}, ...) with {@code 400 BAD_REQUEST} as an
 * {@link ApiError}, instead of the error page of Spring Boot. Spring Security picks this bean up by
 * its type.
 *
 * <p>The reason is not told to the client: the message of the exception quotes the URL.
 */
@Component
public class ApiRequestRejectedHandler implements RequestRejectedHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiRequestRejectedHandler.class);

    private final ApiErrorWriter writer;

    public ApiRequestRejectedHandler(ApiErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, RequestRejectedException e)
            throws IOException {
        log.debug("Request rejected by the firewall: {}", e.getMessage());
        writer.write(response, ApiError.of(HttpStatus.BAD_REQUEST));
    }
}
