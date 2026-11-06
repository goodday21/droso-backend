package org.example.ticket;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.example.text.InvisibleText;

/**
 * A ticket reported by a user. The table is created by the migration
 * {@code V2__create_defects.sql} as {@code defects} and renamed by {@code V10__rename_defects_to_tickets.sql}.
 *
 * <p>A new ticket always has the status {@link TicketStatus#NEW}; its priority defaults to
 * {@link TicketPriority#MEDIUM}, its type to {@link TicketType#FEATURE}. A ticket is soft-deleted:
 * {@code deletedAt} is set and the row stays, and a deleted ticket is absent for the API. Both timestamps are set by the application when the ticket is
 * persisted for the first time.
 */
@Entity
@Table(name = "tickets")
public class Ticket {

    // The limits of the text fields, in characters (code points), as in the VARCHAR columns.
    // Public, so that the request DTOs validate the same limits instead of repeating the numbers.
    public static final int TITLE_MIN_LENGTH = 5;
    public static final int TITLE_MAX_LENGTH = 120;
    public static final int DESCRIPTION_MAX_LENGTH = 2000;
    public static final int STEPS_TO_REPRODUCE_MAX_LENGTH = 5000;
    public static final int EXPECTED_RESULT_MAX_LENGTH = 2000;
    public static final int ACTUAL_RESULT_MAX_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    @Column(name = "description", length = DESCRIPTION_MAX_LENGTH)
    private String description;

    @Column(name = "steps_to_reproduce", length = STEPS_TO_REPRODUCE_MAX_LENGTH)
    private String stepsToReproduce;

    @Column(name = "expected_result", length = EXPECTED_RESULT_MAX_LENGTH)
    private String expectedResult;

    @Column(name = "actual_result", length = ACTUAL_RESULT_MAX_LENGTH)
    private String actualResult;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 16)
    private TicketPriority priority = TicketPriority.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TicketStatus status = TicketStatus.NEW;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private TicketType type = TicketType.FEATURE;

    /**
     * The id of the user who reported the ticket; {@code null} for the tickets created before the
     * authors were recorded ({@code V6}). An id rather than a relation to {@code User}: the ticket
     * needs no more of the user, and a lazy relation would fail outside the transaction.
     */
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** {@code null} while the ticket is active. Nothing sets it yet: there is no deletion. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** Required by JPA. */
    protected Ticket() {
    }

    /**
     * @param title            required; 5..120 characters after trimming the invisible characters
     *                         ({@link InvisibleText}), stored trimmed
     * @param description      optional; {@code null} when invisible as a whole, otherwise kept as given
     * @param stepsToReproduce optional; as {@code description}
     * @param expectedResult   optional; as {@code description}
     * @param actualResult     optional; as {@code description}
     * @param priority         optional; {@link TicketPriority#MEDIUM} when {@code null}
     * @param type             optional; {@link TicketType#FEATURE} when {@code null}
     * @param createdBy        required; the id of the user who reports the ticket
     * @throws IllegalArgumentException if the title violates its length rule or the author is missing
     */
    public Ticket(String title, String description, String stepsToReproduce, String expectedResult,
            String actualResult, TicketPriority priority, TicketType type, Long createdBy) {
        this.title = normalizeTitle(title);
        this.description = InvisibleText.blankToNull(description);
        this.stepsToReproduce = InvisibleText.blankToNull(stepsToReproduce);
        this.expectedResult = InvisibleText.blankToNull(expectedResult);
        this.actualResult = InvisibleText.blankToNull(actualResult);
        if (priority != null) {
            this.priority = priority;
        }
        if (type != null) {
            this.type = type;
        }
        if (createdBy == null) {
            throw new IllegalArgumentException("The author is required");
        }
        this.createdBy = createdBy;
    }

    private static String normalizeTitle(String title) {
        if (title == null) {
            throw new IllegalArgumentException("The title is required");
        }
        String trimmed = InvisibleText.strip(title);
        // Code points rather than UTF-16 units, so that the rule counts characters the way the
        // VARCHAR(120) column does.
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < TITLE_MIN_LENGTH || length > TITLE_MAX_LENGTH) {
            throw new IllegalArgumentException("The title must be " + TITLE_MIN_LENGTH + ".."
                    + TITLE_MAX_LENGTH + " characters long after trimming, but is " + length);
        }
        return trimmed;
    }

    /**
     * Sets both timestamps to the same moment. PostgreSQL stores microseconds, so the moment is
     * truncated to them: the values read back are then equal to the ones that were written.
     */
    @PrePersist
    void initializeTimestamps() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getStepsToReproduce() {
        return stepsToReproduce;
    }

    public String getExpectedResult() {
        return expectedResult;
    }

    public String getActualResult() {
        return actualResult;
    }

    public TicketPriority getPriority() {
        return priority;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public TicketType getType() {
        return type;
    }

    /** {@code null} only for a ticket created before the authors were recorded. */
    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** {@code null} while the ticket is active. */
    public Instant getDeletedAt() {
        return deletedAt;
    }

    public boolean isActive() {
        return deletedAt == null;
    }
}
