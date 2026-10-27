package org.example.defect;

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

/**
 * A defect reported by a user. The table is created by the migration {@code V2__create_defects.sql}.
 *
 * <p>A new defect always has the status {@link DefectStatus#NEW}; its priority defaults to
 * {@link DefectPriority#MEDIUM}. Both timestamps are set by the application when the defect is
 * persisted for the first time.
 */
@Entity
@Table(name = "defects")
public class Defect {

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
    private DefectPriority priority = DefectPriority.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DefectStatus status = DefectStatus.NEW;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected Defect() {
    }

    /**
     * @param title            required; 5..120 characters after trimming the invisible characters
     *                         ({@link InvisibleText}), stored trimmed
     * @param description      optional; {@code null} when invisible as a whole, otherwise kept as given
     * @param stepsToReproduce optional; as {@code description}
     * @param expectedResult   optional; as {@code description}
     * @param actualResult     optional; as {@code description}
     * @param priority         optional; {@link DefectPriority#MEDIUM} when {@code null}
     * @throws IllegalArgumentException if the title violates its length rule
     */
    public Defect(String title, String description, String stepsToReproduce, String expectedResult,
            String actualResult, DefectPriority priority) {
        this.title = normalizeTitle(title);
        this.description = InvisibleText.blankToNull(description);
        this.stepsToReproduce = InvisibleText.blankToNull(stepsToReproduce);
        this.expectedResult = InvisibleText.blankToNull(expectedResult);
        this.actualResult = InvisibleText.blankToNull(actualResult);
        if (priority != null) {
            this.priority = priority;
        }
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

    public DefectPriority getPriority() {
        return priority;
    }

    public DefectStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
