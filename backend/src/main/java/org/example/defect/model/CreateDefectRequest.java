package org.example.defect.model;

import static org.example.defect.Defect.ACTUAL_RESULT_MAX_LENGTH;
import static org.example.defect.Defect.DESCRIPTION_MAX_LENGTH;
import static org.example.defect.Defect.EXPECTED_RESULT_MAX_LENGTH;
import static org.example.defect.Defect.STEPS_TO_REPRODUCE_MAX_LENGTH;
import static org.example.defect.Defect.TITLE_MAX_LENGTH;
import static org.example.defect.Defect.TITLE_MIN_LENGTH;

import jakarta.validation.constraints.NotBlank;
import org.example.defect.DefectPriority;
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
 * @param title            required; 5..120 characters after stripping the surrounding whitespace
 * @param description      optional, at most 2000 characters
 * @param stepsToReproduce optional, at most 5000 characters
 * @param expectedResult   optional, at most 2000 characters
 * @param actualResult     optional, at most 2000 characters
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
     * Strips the title before it is validated, so that the length rule applies to the title as it
     * will be stored. {@code Defect} strips it the same way.
     */
    public CreateDefectRequest {
        title = title == null ? null : title.strip();
    }
}
