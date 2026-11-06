package org.example.ticket.model;

import static org.example.ticket.Ticket.ACTUAL_RESULT_MAX_LENGTH;
import static org.example.ticket.Ticket.DESCRIPTION_MAX_LENGTH;
import static org.example.ticket.Ticket.EXPECTED_RESULT_MAX_LENGTH;
import static org.example.ticket.Ticket.STEPS_TO_REPRODUCE_MAX_LENGTH;
import static org.example.ticket.Ticket.TITLE_MAX_LENGTH;
import static org.example.ticket.Ticket.TITLE_MIN_LENGTH;

import jakarta.validation.constraints.NotBlank;
import org.example.ticket.TicketPriority;
import org.example.ticket.TicketType;
import org.example.text.InvisibleText;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * Request body of {@code POST /api/v1/tickets}.
 *
 * <p>The client sets the content of the ticket only: the id, the status, the author and the timestamps are
 * chosen by the server. Since unknown JSON properties are rejected
 * ({@code spring.jackson.deserialization.fail-on-unknown-properties}), a request that tries to set
 * them answers {@code 400} instead of being silently ignored.
 *
 * <p>The lengths are counted in code points by {@link CodePointLength}, the way {@code Ticket} and
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
 * @param priority         optional; {@link TicketPriority#MEDIUM} when absent or {@code null}
 * @param type             optional; {@link TicketType#FEATURE} when absent or {@code null}
 */
public record CreateTicketRequest(
        @NotBlank @CodePointLength(min = TITLE_MIN_LENGTH, max = TITLE_MAX_LENGTH) String title,
        @CodePointLength(max = DESCRIPTION_MAX_LENGTH) String description,
        @CodePointLength(max = STEPS_TO_REPRODUCE_MAX_LENGTH) String stepsToReproduce,
        @CodePointLength(max = EXPECTED_RESULT_MAX_LENGTH) String expectedResult,
        @CodePointLength(max = ACTUAL_RESULT_MAX_LENGTH) String actualResult,
        TicketPriority priority,
        TicketType type) {

    /**
     * Normalizes the text before it is validated, so that the rules apply to the values as they
     * will be stored; {@code Ticket} normalizes them the same way. An invisible optional field
     * becomes {@code null} and is therefore not subject to its length limit.
     */
    public CreateTicketRequest {
        title = InvisibleText.strip(title);
        description = InvisibleText.blankToNull(description);
        stepsToReproduce = InvisibleText.blankToNull(stepsToReproduce);
        expectedResult = InvisibleText.blankToNull(expectedResult);
        actualResult = InvisibleText.blankToNull(actualResult);
    }
}
