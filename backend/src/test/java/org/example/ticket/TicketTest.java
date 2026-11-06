package org.example.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class TicketTest {

    /** The id of the reporting user; the entity does not check that it exists, the foreign key does. */
    private static final Long AUTHOR = 7L;

    private static Ticket withTitle(String title) {
        return new Ticket(title, null, null, null, null, null, null, AUTHOR);
    }

    @Test
    @DisplayName("a new ticket has the priority MEDIUM when none is given")
    void defaultPriority() {
        assertThat(withTitle("Login fails").getPriority()).isEqualTo(TicketPriority.MEDIUM);
    }

    @Test
    @DisplayName("a given priority is kept")
    void givenPriority() {
        Ticket ticket = new Ticket("Login fails", null, null, null, null, TicketPriority.CRITICAL, null, AUTHOR);

        assertThat(ticket.getPriority()).isEqualTo(TicketPriority.CRITICAL);
    }

    @Test
    @DisplayName("a new ticket has the type FEATURE when none is given")
    void defaultType() {
        assertThat(withTitle("Login fails").getType()).isEqualTo(TicketType.FEATURE);
    }

    @Test
    @DisplayName("a given type is kept")
    void givenType() {
        Ticket ticket = new Ticket("Login fails", null, null, null, null, null, TicketType.BUG, AUTHOR);

        assertThat(ticket.getType()).isEqualTo(TicketType.BUG);
    }

    @Test
    @DisplayName("a new ticket is active")
    void newTicketIsActive() {
        Ticket ticket = withTitle("Login fails");

        assertThat(ticket.isActive()).isTrue();
        assertThat(ticket.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("a new ticket always has the status NEW")
    void statusIsNew() {
        assertThat(withTitle("Login fails").getStatus()).isEqualTo(TicketStatus.NEW);
    }

    @Test
    @DisplayName("a new ticket has neither an id nor timestamps before it is persisted")
    void noIdAndTimestampsBeforePersist() {
        Ticket ticket = withTitle("Login fails");

        assertThat(ticket.getId()).isNull();
        assertThat(ticket.getCreatedAt()).isNull();
        assertThat(ticket.getUpdatedAt()).isNull();
    }

    @Test
    @DisplayName("the optional fields are kept as given")
    void optionalFields() {
        Ticket ticket = new Ticket("Login fails", "description", "steps", "expected", "actual", null, null, AUTHOR);

        assertThat(ticket.getDescription()).isEqualTo("description");
        assertThat(ticket.getStepsToReproduce()).isEqualTo("steps");
        assertThat(ticket.getExpectedResult()).isEqualTo("expected");
        assertThat(ticket.getActualResult()).isEqualTo("actual");
    }

    @Test
    @DisplayName("an optional field of invisible characters only is stored as null")
    void blankOptionalFieldsAreNull() {
        Ticket ticket = new Ticket("Login fails", "", "  \n\t ", "  ", "​﻿", null, null, AUTHOR);

        assertThat(ticket.getDescription()).isNull();
        assertThat(ticket.getStepsToReproduce()).isNull();
        assertThat(ticket.getExpectedResult()).isNull();
        assertThat(ticket.getActualResult()).isNull();
    }

    @Test
    @DisplayName("an optional field with visible text is kept as given, its whitespace included")
    void optionalFieldsAreNotTrimmed() {
        Ticket ticket = new Ticket("Login fails", " description\n", " steps", null, null, null, null, AUTHOR);

        assertThat(ticket.getDescription()).isEqualTo(" description\n");
        assertThat(ticket.getStepsToReproduce()).isEqualTo(" steps");
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
        Ticket ticket = withTitle("Login fails");
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        ticket.initializeTimestamps();

        Instant after = Instant.now();
        assertThat(ticket.getCreatedAt()).isBetween(before, after);
        assertThat(ticket.getUpdatedAt()).isEqualTo(ticket.getCreatedAt());
        assertThat(ticket.getCreatedAt().truncatedTo(ChronoUnit.MICROS)).isEqualTo(ticket.getCreatedAt());
    }
}
