package org.example.defect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class DefectTest {

    /** The id of the reporting user; the entity does not check that it exists, the foreign key does. */
    private static final Long AUTHOR = 7L;

    private static Defect withTitle(String title) {
        return new Defect(title, null, null, null, null, null, AUTHOR);
    }

    @Test
    @DisplayName("a new defect has the priority MEDIUM when none is given")
    void defaultPriority() {
        assertThat(withTitle("Login fails").getPriority()).isEqualTo(DefectPriority.MEDIUM);
    }

    @Test
    @DisplayName("a given priority is kept")
    void givenPriority() {
        Defect defect = new Defect("Login fails", null, null, null, null, DefectPriority.CRITICAL, AUTHOR);

        assertThat(defect.getPriority()).isEqualTo(DefectPriority.CRITICAL);
    }

    @Test
    @DisplayName("a new defect always has the status NEW")
    void statusIsNew() {
        assertThat(withTitle("Login fails").getStatus()).isEqualTo(DefectStatus.NEW);
    }

    @Test
    @DisplayName("a new defect has neither an id nor timestamps before it is persisted")
    void noIdAndTimestampsBeforePersist() {
        Defect defect = withTitle("Login fails");

        assertThat(defect.getId()).isNull();
        assertThat(defect.getCreatedAt()).isNull();
        assertThat(defect.getUpdatedAt()).isNull();
    }

    @Test
    @DisplayName("the optional fields are kept as given")
    void optionalFields() {
        Defect defect = new Defect("Login fails", "description", "steps", "expected", "actual", null, AUTHOR);

        assertThat(defect.getDescription()).isEqualTo("description");
        assertThat(defect.getStepsToReproduce()).isEqualTo("steps");
        assertThat(defect.getExpectedResult()).isEqualTo("expected");
        assertThat(defect.getActualResult()).isEqualTo("actual");
    }

    @Test
    @DisplayName("an optional field of invisible characters only is stored as null")
    void blankOptionalFieldsAreNull() {
        Defect defect = new Defect("Login fails", "", "  \n\t ", "  ", "​﻿", null, AUTHOR);

        assertThat(defect.getDescription()).isNull();
        assertThat(defect.getStepsToReproduce()).isNull();
        assertThat(defect.getExpectedResult()).isNull();
        assertThat(defect.getActualResult()).isNull();
    }

    @Test
    @DisplayName("an optional field with visible text is kept as given, its whitespace included")
    void optionalFieldsAreNotTrimmed() {
        Defect defect = new Defect("Login fails", " description\n", " steps", null, null, null, AUTHOR);

        assertThat(defect.getDescription()).isEqualTo(" description\n");
        assertThat(defect.getStepsToReproduce()).isEqualTo(" steps");
    }

    @Test
    @DisplayName("the title is stored trimmed")
    void titleIsTrimmed() {
        assertThat(withTitle("  \tLogin fails \n").getTitle()).isEqualTo("Login fails");
    }

    @Test
    @DisplayName("the title accepts 5 and 120 characters after trimming")
    void titleBoundsAreAccepted() {
        assertThat(withTitle("  12345  ").getTitle()).isEqualTo("12345");
        assertThat(withTitle(" " + "a".repeat(120) + " ").getTitle()).hasSize(120);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "     ", "1234", "  1234  "})
    @DisplayName("the title is rejected when it is missing or shorter than 5 characters after trimming")
    void tooShortTitleIsRejected(String title) {
        assertThatIllegalArgumentException().isThrownBy(() -> withTitle(title));
    }

    @ParameterizedTest
    @ValueSource(strings = {"     ", "     ",
            "     ", "​​​​​", "﻿﻿﻿﻿﻿",
            "  abcd​​"})
    @DisplayName("no-break and zero-width spaces are trimmed as well before the length is checked")
    void invisibleCharactersDoNotCount(String title) {
        assertThatIllegalArgumentException().isThrownBy(() -> withTitle(title));
    }

    @Test
    @DisplayName("invisible characters are trimmed around the title only")
    void invisibleCharactersAreTrimmedAroundTheTitle() {
        assertThat(withTitle("﻿  Login fails ​").getTitle()).isEqualTo("Login fails");
    }

    @Test
    @DisplayName("the title is rejected when it is longer than 120 characters after trimming")
    void tooLongTitleIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> withTitle("a".repeat(121)));
    }

    @Test
    @DisplayName("the title length counts characters, not UTF-16 units")
    void titleLengthCountsCodePoints() {
        // Each emoji is one character but two UTF-16 units: 120 characters, 240 units.
        String emojis = "🐞".repeat(120);

        assertThat(withTitle(emojis).getTitle()).isEqualTo(emojis);
        assertThatIllegalArgumentException().isThrownBy(() -> withTitle(emojis + "🐞"));
    }

    @Test
    @DisplayName("persisting sets both timestamps to the same current moment, in microseconds")
    void timestampsAreInitialized() {
        Defect defect = withTitle("Login fails");
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        defect.initializeTimestamps();

        Instant after = Instant.now();
        assertThat(defect.getCreatedAt()).isBetween(before, after);
        assertThat(defect.getUpdatedAt()).isEqualTo(defect.getCreatedAt());
        assertThat(defect.getCreatedAt().truncatedTo(ChronoUnit.MICROS)).isEqualTo(defect.getCreatedAt());
    }
}
