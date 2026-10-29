package org.example.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.example.auth.model.RegisterRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The normalization of {@link RegisterRequest} and how its constraints apply the rules of
 * {@code EmailAddress} and {@code PasswordPolicy} (pinned in detail by their own tests), with the
 * Bean Validation provider of the application but without Spring.
 *
 * <p>How the violations reach the client is pinned by {@link RegistrationIntegrationTest}, which
 * keeps one case per kind of error.
 */
class RegisterRequestTest {

    private static final String PASSWORD = "Sup3r-Secret!";
    private static final String MALFORMED_EMAIL = "must be a well-formed email address";
    private static final String PASSWORD_CHARACTERS = "must contain only Latin letters, digits and punctuation characters";

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    /** The messages of the violations per field, sorted. */
    private static Map<String, Set<String>> violations(RegisterRequest request) {
        Map<String, Set<String>> violations = new TreeMap<>();
        for (ConstraintViolation<RegisterRequest> violation : VALIDATOR.validate(request)) {
            violations.computeIfAbsent(violation.getPropertyPath().toString(), field -> new TreeSet<>())
                    .add(violation.getMessage());
        }
        return violations;
    }

    @Test
    @DisplayName("the email is normalized, the password is kept exactly as given")
    void normalization() {
        RegisterRequest request = new RegisterRequest(" Qwe@GMail.com ", " Abcdef1! ");

        assertThat(request.email()).isEqualTo("qwe@gmail.com");
        assertThat(request.password()).isEqualTo(" Abcdef1! ");
    }

    @Test
    @DisplayName("toString() does not reveal the password")
    void toStringHidesThePassword() {
        RegisterRequest request = new RegisterRequest("qwe@gmail.com", PASSWORD);

        assertThat(request.toString()).doesNotContain(PASSWORD).contains("qwe@gmail.com");
    }

    @Test
    @DisplayName("a valid request has no violation")
    void validRequest() {
        assertThat(violations(new RegisterRequest(" Qwe@GMail.com ", PASSWORD))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", " \u200B"})
    @DisplayName("a blank email is reported once, as blank, not as malformed")
    void blankEmail(String email) {
        assertThat(violations(new RegisterRequest(email, PASSWORD)))
                .isEqualTo(Map.of("email", Set.of("must not be blank")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a@b", "qwe@gmail", "qwe..qwe@gmail.com", "\"qwe\"@gmail.com", "qwe@пример.рф"})
    @DisplayName("a malformed email is reported for the field")
    void malformedEmail(String email) {
        assertThat(violations(new RegisterRequest(email, PASSWORD)))
                .isEqualTo(Map.of("email", Set.of(MALFORMED_EMAIL)));
    }

    @Test
    @DisplayName("an email longer than 254 characters is malformed")
    void tooLongEmail() {
        String email = "x".repeat(64) + "@" + "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + ".com";

        assertThat(violations(new RegisterRequest(email, PASSWORD)))
                .isEqualTo(Map.of("email", Set.of(MALFORMED_EMAIL)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Sup3r Secret!", "Sup3r-Secret! ", " Sup3r-Secret!", "Sup3r-Секрет!", "Sup3r-Secret!😀"})
    @DisplayName("a password with a space or a character other than printable ASCII is reported, not stripped")
    void passwordCharacters(String password) {
        assertThat(violations(new RegisterRequest("qwe@gmail.com", password)))
                .isEqualTo(Map.of("password", Set.of(PASSWORD_CHARACTERS)));
    }

    @Test
    @DisplayName("every violated password rule is a violation of its own")
    void weakPassword() {
        assertThat(violations(new RegisterRequest("qwe@gmail.com", "secret"))).isEqualTo(Map.of("password", Set.of(
                "must be 8 to 64 characters long", "must contain a digit",
                "must contain a punctuation character", "must contain an upper case letter")));
    }

    @Test
    @DisplayName("missing fields are reported for each field")
    void missingFields() {
        assertThat(violations(new RegisterRequest(null, null))).isEqualTo(Map.of(
                "email", Set.of("must not be blank"),
                "password", Set.of("must not be null")));
    }
}
