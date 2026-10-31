package org.example.security;

import java.io.IOException;

import org.example.web.error.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.cors.DefaultCorsProcessor;

/**
 * The CORS checks of Spring, with the rejection answered as {@code 403 FORBIDDEN} {@link ApiError}
 * instead of the plain text {@code Invalid CORS request}. Like the other handlers of this package,
 * it runs in a filter, before Spring MVC.
 */
class ApiCorsProcessor extends DefaultCorsProcessor {

    private static final Logger log = LoggerFactory.getLogger(ApiCorsProcessor.class);

    private final ApiErrorWriter writer;

    ApiCorsProcessor(ApiErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    protected void rejectRequest(ServerHttpResponse response) throws IOException {
        log.debug("Cross-origin request rejected with status 403");
        writer.write(((ServletServerHttpResponse) response).getServletResponse(), ApiError.of(HttpStatus.FORBIDDEN));
    }
}
