package org.example.defect.model;

import static org.example.defect.Defect.ACTUAL_RESULT_MAX_LENGTH;
import static org.example.defect.Defect.DESCRIPTION_MAX_LENGTH;
import static org.example.defect.Defect.EXPECTED_RESULT_MAX_LENGTH;
import static org.example.defect.Defect.STEPS_TO_REPRODUCE_MAX_LENGTH;
import static org.example.defect.Defect.TITLE_MAX_LENGTH;
import static org.example.defect.Defect.TITLE_MIN_LENGTH;

import jakarta.validation.constraints.NotBlank;
import org.example.defect.DefectPriority;
import org.example.defect.InvisibleText;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * Request body of {@code POST /api/v1/defects}.
 *
 * <p>The client sets the content of the defect only: the id, the status and the timestamps are
 * chosen by the server. Since unknown JSON properties are rejected
 * ({@code spring.jackson.deserialization.fail-on-unknown-properties}), a request that tries to set
 * them answers {@code 400} instead of being silently ignored.
 *
 * <p>The lengths are counted in code points by {@link CodePointLength}, the way {@code Defect} and
 * the {@code VARCHAR} columns count them; {@code @Size} counts UTF-16 units and would let a title
 * of four emoji pass here only to be rejected by the entity.
 *
 * <p>"Whitespace" and "invisible" include the no-break and the zero-width spaces, which
 * {@code String.strip()} and {@code @NotBlank} alone do not know: see {@link InvisibleText}.
 *
 * @param title            required; 5..120 characters after stripping the surrounding invisible
 *                         characters
 * @param description      optional, at most 2000 characters; {@code null} when invisible as a whole
 * @param stepsToReproduce optional, at most 5000 characters; as {@code description}
 * @param expectedResult   optional, at most 2000 characters; as {@code description}
 * @param actualResult     optional, at most 2000 characters; as {@code description}
 * @param priority         optional; {@link DefectPriority#MEDIUM} when absent or {@code null}
 */
public record CreateDefectRequest(
        @NotBlank @CodePointLength(min = TITLE_MIN_LENGTH, max = TITLE_MAX_LENGTH) String title,
        @CodePointLength(max = DESCRIPTION_MAX_LENGTH) String description,
        @CodePointLength(max = STEPS_TO_REPRODUCE_MAX_LENGTH) String stepsToReproduce,
        @CodePointLength(max = EXPECTED_RESULT_MAX_LENGTH) String expectedResult,
        @CodePointLength(max = ACTUAL_RESULT_MAX_LENGTH) String actualResult,
        DefectPriority priority) {

    /**
     * Normalizes the text before it is validated, so that the rules apply to the values as they
     * will be stored; {@code Defect} normalizes them the same way. An invisible optional field
     * becomes {@code null} and is therefore not subject to its length limit.
     */
    public CreateDefectRequest {
        title = InvisibleText.strip(title);
        description = InvisibleText.blankToNull(description);
        stepsToReproduce = InvisibleText.blankToNull(stepsToReproduce);
        expectedResult = InvisibleText.blankToNull(expectedResult);
        actualResult = InvisibleText.blankToNull(actualResult);
    }
}
