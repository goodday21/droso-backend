package org.example.defect;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.example.defect.model.CreateDefectRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The normalization and the validation rules of {@link CreateDefectRequest}, with the Bean
 * Validation provider of the application but without Spring.
 *
 * <p>How the violations reach the client (status, code, joined messages, sorting) is pinned by
 * {@link DefectCreationIntegrationTest}, which keeps one case per kind of error.
 */
class CreateDefectRequestTest {

    /** One code point, two UTF-16 units. */
    private static final String EMOJI = "😀";

    private static final String TITLE_LENGTH = "length must be between 5 and 120";
    private static final String BLANK = "must not be blank";

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    private static CreateDefectRequest withTitle(String title) {
        return new CreateDefectRequest(title, null, null, null, null, null);
    }

    /** A valid request with {@code value} in the optional text field {@code field}. */
    private static CreateDefectRequest withField(String field, String value) {
        String title = "Crash on start";
        return switch (field) {
            case "description" -> new CreateDefectRequest(title, value, null, null, null, null);
            case "stepsToReproduce" -> new CreateDefectRequest(title, null, value, null, null, null);
            case "expectedResult" -> new CreateDefectRequest(title, null, null, value, null, null);
            case "actualResult" -> new CreateDefectRequest(title, null, null, null, value, null);
            default -> throw new IllegalArgumentException(field);
        };
    }

    private static String field(CreateDefectRequest request, String field) {
        return switch (field) {
            case "description" -> request.description();
            case "stepsToReproduce" -> request.stepsToReproduce();
            case "expectedResult" -> request.expectedResult();
            case "actualResult" -> request.actualResult();
            default -> throw new IllegalArgumentException(field);
        };
    }

    /** The messages of the violations per field, sorted. */
    private static Map<String, Set<String>> violations(CreateDefectRequest request) {
        Map<String, Set<String>> violations = new TreeMap<>();
        for (ConstraintViolation<CreateDefectRequest> violation : VALIDATOR.validate(request)) {
            violations.computeIfAbsent(violation.getPropertyPath().toString(), field -> new TreeSet<>())
                    .add(violation.getMessage());
        }
        return violations;
    }

    // ---------------------------------------------------------------- title

    @ParameterizedTest
    @ValueSource(ints = {5, 120})
    @DisplayName("a title of 5 and of 120 characters is valid")
    void titleBoundsAreValid(int length) {
        assertThat(violations(withTitle("a".repeat(length)))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 121})
    @DisplayName("a title of 4 or of 121 characters violates the length")
    void titleOutOfBounds(int length) {
        assertThat(violations(withTitle("a".repeat(length)))).isEqualTo(Map.of("title", Set.of(TITLE_LENGTH)));
    }

    @Test
    @DisplayName("the length of the title is counted in code points, not in UTF-16 units")
    void titleCountsCodePoints() {
        assertThat(violations(withTitle(EMOJI.repeat(120)))).isEmpty();
        assertThat(violations(withTitle(EMOJI.repeat(4)))).isEqualTo(Map.of("title", Set.of(TITLE_LENGTH)));
    }

    @Test
    @DisplayName("a title of 120 characters surrounded by whitespace is stripped before it is validated")
    void titleIsStrippedBeforeValidation() {
        CreateDefectRequest request = withTitle("   " + "a".repeat(120) + "   ");

        assertThat(request.title()).isEqualTo("a".repeat(120));
        assertThat(violations(request)).isEmpty();
    }

    @Test
    @DisplayName("a null title is blank")
    void nullTitle() {
        assertThat(violations(withTitle(null))).isEqualTo(Map.of("title", Set.of(BLANK)));
    }

    /**
     * Empty, whitespace, and the characters that {@code String.strip()} keeps: the no-break spaces
     * are not {@code Character.isWhitespace}, the zero-width ones are not even space characters.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            " \t\n ",
            "\u00A0\u00A0\u00A0\u00A0\u00A0",   // NO-BREAK SPACE
            "\u2007\u2007\u2007\u2007\u2007",   // FIGURE SPACE
            "\u202F\u202F\u202F\u202F\u202F",   // NARROW NO-BREAK SPACE
            "\u200B\u200B\u200B\u200B\u200B",   // ZERO WIDTH SPACE
            "\u2060\u2060\u2060\u2060\u2060",   // WORD JOINER
            "\uFEFF\uFEFF\uFEFF\uFEFF\uFEFF",   // ZERO WIDTH NO-BREAK SPACE (BOM)
            "\u3000\u3000\u3000\u3000\u3000",   // IDEOGRAPHIC SPACE
            " \u00A0\t\u200B\n\uFEFF\u202F "})
    @DisplayName("a title of whitespace or invisible characters only is blank and too short")
    void blankTitle(String title) {
        assertThat(violations(withTitle(title))).isEqualTo(Map.of("title", Set.of(BLANK, TITLE_LENGTH)));
    }

    @Test
    @DisplayName("invisible characters around the title are stripped, the ones inside are kept")
    void invisibleCharactersAroundTheTitle() {
        assertThat(withTitle("\uFEFF\u00A0 Login\u00A0fails \u200B ").title()).isEqualTo("Login\u00A0fails");
    }

    @ParameterizedTest
    @ValueSource(strings = {"  abcd  ", "\u00A0\u00A0abcd\u200B\u200B"})
    @DisplayName("a title shorter than 5 characters after stripping violates the length")
    void shortTitleAfterStripping(String title) {
        assertThat(violations(withTitle(title))).isEqualTo(Map.of("title", Set.of(TITLE_LENGTH)));
    }

    // ---------------------------------------------------------------- optional text fields

    @ParameterizedTest(name = "{0}: {1} characters")
    @CsvSource({
            "description,      2000",
            "stepsToReproduce, 5000",
            "expectedResult,   2000",
            "actualResult,     2000"})
    @DisplayName("an optional field may have its maximal length in code points, not one more")
    void optionalFieldLength(String field, int max) {
        assertThat(violations(withField(field, "x".repeat(max - 1) + EMOJI))).isEmpty();
        assertThat(violations(withField(field, "x".repeat(max + 1))))
                .isEqualTo(Map.of(field, Set.of("length must be between 0 and " + max)));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"description", "stepsToReproduce", "expectedResult", "actualResult"})
    @DisplayName("an optional field of invisible characters only becomes null, whatever its length")
    void invisibleOptionalFieldIsNull(String field) {
        for (String blank : new String[] {"", "  \t\n  ", " \u200B\uFEFF ", " ".repeat(6000)}) {
            CreateDefectRequest request = withField(field, blank);

            assertThat(field(request, field)).isNull();
            assertThat(violations(request)).isEmpty();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"description", "stepsToReproduce", "expectedResult", "actualResult"})
    @DisplayName("an optional field with visible text is kept exactly as sent, whitespace included")
    void visibleOptionalFieldIsKept(String field) {
        String value = "  1. abc\n2. абв\n\n3.PROFIT \n";

        assertThat(field(withField(field, value), field)).isEqualTo(value);
    }
}
