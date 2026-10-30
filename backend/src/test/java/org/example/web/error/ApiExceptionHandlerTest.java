package org.example.web.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.example.auth.EmailAlreadyRegisteredException;
import org.example.auth.RegistrationDisabledException;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every kind of error answered by {@link ApiExceptionHandler}, within the real Spring MVC
 * configuration of the application.
 *
 * <p>No endpoint of the API accepts a body or a parameter yet, so the requests go to
 * {@link ProbeController}, which exists in this test only. Being nested in a test class, it is
 * ignored by the component scan of the other Spring Boot tests and has to be imported explicitly.
 */
@WebMvcTest(controllers = ApiExceptionHandlerTest.ProbeController.class)
// Without the filters of Spring Security: this test is about the error handling of Spring MVC, and
// the slice would apply the default security of Spring Boot, not SecurityConfig. The security of
// the API is tested against the whole application (JwtAuthenticationIntegrationTest).
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandlerTest.ProbeController.class)
class ApiExceptionHandlerTest {

    private static final String SECRET = "Connection to jdbc:postgresql://10.0.0.5:5432/secret_db failed";

    @Autowired
    private MockMvc mockMvc;

    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        enum Level { LOW, HIGH }

        record ProbeBody(@NotBlank String title, @Size(max = 5) @Pattern(regexp = "[a-z]*") String note, Level level) {
        }

        @PostMapping("/body")
        void body(@Valid @RequestBody ProbeBody body) {
        }

        @GetMapping("/items/{id}")
        void item(@PathVariable Long id) {
            throw new ResourceNotFoundException("Item", id);
        }

        @GetMapping("/page")
        void page(@RequestParam @Min(1) int size) {
        }

        @GetMapping("/taken")
        void taken() {
            throw new EmailAlreadyRegisteredException(new IllegalStateException(SECRET));
        }

        @GetMapping("/closed")
        void closed() {
            throw new RegistrationDisabledException();
        }

        /** What a denied {@code @PreAuthorize} throws inside Spring MVC. */
        @GetMapping("/denied")
        void denied() {
            throw new AuthorizationDeniedException(SECRET);
        }

        @GetMapping("/failure")
        void failure() {
            throw new IllegalStateException(SECRET);
        }
    }

    private ResultActions postJson(String json) throws Exception {
        return mockMvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("a violated constraint answers 400 VALIDATION_ERROR with the error of the field")
    void validationError() throws Exception {
        postJson("{\"title\": \"  \"}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.fieldErrors.title").value("must not be blank"))
                .andExpect(jsonPath("$.fieldErrors.*", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.*", Matchers.hasSize(4)));
    }

    @Test
    @DisplayName("the validation messages are English whatever language the client asks for")
    void validationMessagesAreEnglish() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON)
                        .header("Accept-Language", "ru-RU,ru;q=0.9")
                        .content("{\"title\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.title").value("must not be blank"));
    }

    @Test
    @DisplayName("every invalid field is reported, several errors of one field are joined in a fixed order")
    void severalFieldErrors() throws Exception {
        postJson("{\"note\": \"TOOLONG\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.title").value("must not be blank"))
                .andExpect(jsonPath("$.fieldErrors.note")
                        .value("must match \"[a-z]*\"; size must be between 0 and 5"))
                .andExpect(jsonPath("$.fieldErrors.*", Matchers.hasSize(2)));
    }

    @Test
    @DisplayName("a syntactically invalid JSON answers 400 MALFORMED_REQUEST without the parser message")
    void malformedJson() throws Exception {
        String body = body(postJson("{\"title\": ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("Malformed request body"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty()));

        assertThat(body).doesNotContain("JSON parse").doesNotContain("jackson").doesNotContain("line:");
    }

    @Test
    @DisplayName("an unknown enum value answers 400 VALIDATION_ERROR for the field, without the value or Java types")
    void unknownEnumValue() throws Exception {
        String body = body(postJson("{\"title\": \"Login fails\", \"level\": \"URGENT\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.fieldErrors.level").value("has an invalid value"))
                .andExpect(jsonPath("$.fieldErrors.*", Matchers.hasSize(1))));

        assertThat(body).doesNotContain("URGENT").doesNotContain("Level").doesNotContain("org.example");
    }

    @Test
    @DisplayName("a value of the wrong JSON type answers 400 VALIDATION_ERROR for the field")
    void wrongJsonType() throws Exception {
        String body = body(postJson("{\"title\": {\"nested\": true}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.title").value("has an invalid value")));

        assertThat(body).doesNotContain("String").doesNotContain("nested");
    }

    @Test
    @DisplayName("an unknown property answers 400 VALIDATION_ERROR naming the property")
    void unknownProperty() throws Exception {
        String body = body(postJson("{\"title\": \"Login fails\", \"severity\": \"high\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.severity").value("is not allowed"))
                .andExpect(jsonPath("$.fieldErrors.*", Matchers.hasSize(1))));

        assertThat(body).doesNotContain("ProbeBody").doesNotContain("known properties");
    }

    @Test
    @DisplayName("a body of the wrong shape as a whole answers 400 MALFORMED_REQUEST")
    void wrongBodyShape() throws Exception {
        postJson("[{\"title\": \"Login fails\"}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @Test
    @DisplayName("a missing body answers 400 MALFORMED_REQUEST")
    void missingBody() throws Exception {
        postJson("")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("an unsupported content type answers 415 UNSUPPORTED_MEDIA_TYPE")
    void unsupportedMediaType() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.TEXT_PLAIN).content("title"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.message").value("Unsupported Media Type"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @Test
    @DisplayName("a missing resource answers 404 RESOURCE_NOT_FOUND without the internal message")
    void resourceNotFound() throws Exception {
        String body = body(mockMvc.perform(get("/probe/items/42"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty()));

        assertThat(body).doesNotContain("Item").doesNotContain("with id");
    }

    @Test
    @DisplayName("a path variable of the wrong type answers 400 VALIDATION_ERROR without naming Java types")
    void pathVariableTypeMismatch() throws Exception {
        String body = body(mockMvc.perform(get("/probe/items/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.id").value("has an invalid value")));

        assertThat(body).doesNotContain("Long").doesNotContain("java").doesNotContain("abc");
    }

    @Test
    @DisplayName("a request parameter that violates a constraint answers 400 VALIDATION_ERROR")
    void parameterConstraint() throws Exception {
        mockMvc.perform(get("/probe/page").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.size").value("must be greater than or equal to 1"));
    }

    @Test
    @DisplayName("a missing request parameter answers 400 VALIDATION_ERROR")
    void missingParameter() throws Exception {
        mockMvc.perform(get("/probe/page"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.size").value("is required"));
    }

    @Test
    @DisplayName("a taken email answers 409 EMAIL_ALREADY_REGISTERED naming the field, without the cause")
    void emailAlreadyRegistered() throws Exception {
        String body = body(mockMvc.perform(get("/probe/taken"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"))
                .andExpect(jsonPath("$.message").value("Email is already registered"))
                .andExpect(jsonPath("$.fieldErrors.email").value("is already registered"))
                .andExpect(jsonPath("$.fieldErrors.*", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.*", Matchers.hasSize(4))));

        assertThat(body).doesNotContain("secret_db").doesNotContain("active user");
    }

    @Test
    @DisplayName("a turned-off registration answers 403 REGISTRATION_DISABLED")
    void registrationDisabled() throws Exception {
        mockMvc.perform(get("/probe/closed"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("REGISTRATION_DISABLED"))
                .andExpect(jsonPath("$.message").value("Registration is disabled"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @Test
    @DisplayName("a permission denied by @PreAuthorize answers 403 FORBIDDEN, not 500, without the message")
    void accessDenied() throws Exception {
        String body = body(mockMvc.perform(get("/probe/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Forbidden"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.*", Matchers.hasSize(4))));

        assertThat(body).doesNotContain("secret_db");
    }

    @Test
    @DisplayName("an unexpected exception answers 500 INTERNAL_ERROR without any technical detail")
    void unexpectedException() throws Exception {
        String body = body(mockMvc.perform(get("/probe/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Internal server error"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.*", Matchers.hasSize(4))));

        assertThat(body)
                .doesNotContain("jdbc:")
                .doesNotContain("secret_db")
                .doesNotContain("10.0.0.5")
                .doesNotContain("IllegalStateException")
                .doesNotContain("trace")
                .doesNotContain("org.example");
    }
}
