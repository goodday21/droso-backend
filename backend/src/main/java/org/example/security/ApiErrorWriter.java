package org.example.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;
import org.example.web.error.ApiError;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes an {@link ApiError} straight into the response, for the filters that run before Spring MVC
 * and therefore cannot return a body to its message converters.
 */
@Component
public class ApiErrorWriter {

    private final JsonMapper mapper;

    public ApiErrorWriter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public void write(HttpServletResponse response, ApiError error) throws IOException {
        response.setStatus(error.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), error);
    }
}
