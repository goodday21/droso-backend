package org.example.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.example.support.PostgresIntegrationTest;
import org.example.support.TestUsers;
import org.example.user.Role;
import org.example.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persists and reads {@link Ticket} through JPA against a real PostgreSQL server whose schema was
 * created by the Flyway migrations.
 *
 * <p>Writing and reading happen in separate transactions, so the entity read back comes from the
 * database and not from the persistence context of the write.
 */
@SpringBootTest
class TicketPersistenceIntegrationTest extends PostgresIntegrationTest {
    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository users;

    /** The id of a stored user, the author of the tickets (tickets.created_by refers to users). */
    private Long author;

    @BeforeEach
    void storeAuthor() {
        author = TestUsers.create(users, Role.USER).getId();
    }

    private Ticket persist(Ticket ticket) {
        return transaction.execute(status -> {
            entityManager.persist(ticket);
            return ticket;
        });
    }

    private Ticket find(Long id) {
        return transaction.execute(status -> entityManager.find(Ticket.class, id));
    }

    @Test
    @DisplayName("a ticket with every field is persisted and read back unchanged")
    void persistsAndReadsEveryField() {
        // createdAt is truncated to microseconds, the clock is not: an untruncated "before" may lie
        // inside the same microsecond, after the stored value.
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Ticket saved = persist(new Ticket("  Login fails  ", "The login form rejects valid credentials",
                "1. Open /login\n2. Enter valid credentials", "The dashboard opens",
                "\"Invalid password\" is shown", TicketPriority.HIGH, TicketType.BUG, author));
        Instant after = Instant.now();

        assertThat(saved.getId()).isNotNull();

        Ticket read = find(saved.getId());

        assertThat(read).isNotSameAs(saved);
        assertThat(read.getId()).isEqualTo(saved.getId());
        assertThat(read.getTitle()).isEqualTo("Login fails");
        assertThat(read.getDescription()).isEqualTo("The login form rejects valid credentials");
        assertThat(read.getStepsToReproduce()).isEqualTo("1. Open /login\n2. Enter valid credentials");
        assertThat(read.getExpectedResult()).isEqualTo("The dashboard opens");
        assertThat(read.getActualResult()).isEqualTo("\"Invalid password\" is shown");
        assertThat(read.getPriority()).isEqualTo(TicketPriority.HIGH);
        assertThat(read.getStatus()).isEqualTo(TicketStatus.NEW);
        assertThat(read.getType()).isEqualTo(TicketType.BUG);
        assertThat(read.isActive()).isTrue();
        assertThat(read.getCreatedBy()).isEqualTo(author);
        assertThat(read.getCreatedAt()).isEqualTo(saved.getCreatedAt()).isBetween(before, after);
        assertThat(read.getUpdatedAt()).isEqualTo(read.getCreatedAt());
    }

    @Test
    @DisplayName("a ticket with the required fields only gets the defaults and empty optional fields")
    void persistsTheDefaults() {
        Ticket saved = persist(new Ticket("Crash on start", null, null, null, null, null, null, author));

        Ticket read = find(saved.getId());

        assertThat(read.getPriority()).isEqualTo(TicketPriority.MEDIUM);
        assertThat(read.getStatus()).isEqualTo(TicketStatus.NEW);
        assertThat(read.getType()).isEqualTo(TicketType.FEATURE);
        assertThat(read.getDeletedAt()).isNull();
        assertThat(read.getDescription()).isNull();
        assertThat(read.getStepsToReproduce()).isNull();
        assertThat(read.getExpectedResult()).isNull();
        assertThat(read.getActualResult()).isNull();
        assertThat(read.getCreatedAt()).isNotNull();
        assertThat(read.getUpdatedAt()).isEqualTo(read.getCreatedAt());
    }

    @Test
    @DisplayName("every ticket gets its own id from the database")
    void idsAreGenerated() {
        Long first = persist(new Ticket("First ticket", null, null, null, null, null, null, author)).getId();
        Long second = persist(new Ticket("Second ticket", null, null, null, null, null, null, author)).getId();

        assertThat(first).isNotNull();
        assertThat(second).isNotNull().isNotEqualTo(first);
    }

    @Test
    @DisplayName("the enums are stored by name and the timestamps as the same absolute moment")
    void storageFormat() {
        Ticket saved = persist(new Ticket("Stored by name", null, null, null, null, TicketPriority.CRITICAL, TicketType.BUG, author));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT priority, status, type, created_by, deleted_at FROM tickets WHERE id = ?", saved.getId());

        assertThat(row.get("priority")).isEqualTo("CRITICAL");
        assertThat(row.get("status")).isEqualTo("NEW");
        assertThat(row.get("type")).isEqualTo("BUG");
        assertThat(row.get("deleted_at")).isNull();
        assertThat(row.get("created_by")).isEqualTo(author);
        // Read with plain JDBC, independently of the mapping of Hibernate.
        assertThat(jdbc.queryForObject("SELECT created_at FROM tickets WHERE id = ?",
                OffsetDateTime.class, saved.getId()).toInstant()).isEqualTo(saved.getCreatedAt());
        assertThat(jdbc.queryForObject("SELECT updated_at FROM tickets WHERE id = ?",
                OffsetDateTime.class, saved.getId()).toInstant()).isEqualTo(saved.getUpdatedAt());
    }

    /**
     * The CHECK constraints of V9 must list every value of the enums: a value missing there would be
     * accepted by the application and fail only when it is first stored.
     */
    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    @DisplayName("the database accepts every status of TicketStatus, read back by Hibernate")
    void everyStatusIsStored(TicketStatus status) {
        long id = persist(new Ticket("Status " + status, null, null, null, null, null, null, author)).getId();

        jdbc.update("UPDATE tickets SET status = ? WHERE id = ?", status.name(), id);

        assertThat(find(id).getStatus()).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(TicketType.class)
    @DisplayName("the database accepts every type of TicketType")
    void everyTypeIsStored(TicketType type) {
        long id = persist(new Ticket("Type " + type, null, null, null, null, null, type, author)).getId();

        assertThat(find(id).getType()).isEqualTo(type);
    }

    @Test
    @DisplayName("an author that is not a stored user is rejected by the foreign key")
    void unknownAuthorIsRejected() {
        Ticket ticket = new Ticket("Orphan ticket", null, null, null, null, null, null, author + 1000);

        assertThatThrownBy(() -> persist(ticket))
                .isInstanceOf(PersistenceException.class)
                .rootCause().hasMessageContaining("tickets_created_by_fkey");
    }

    @Test
    @DisplayName("an optional field longer than its column is rejected by the database")
    void tooLongOptionalFieldIsRejected() {
        Ticket ticket = new Ticket("Too long description", "a".repeat(2001), null, null, null, null, null, author);

        assertThatThrownBy(() -> persist(ticket))
                .isInstanceOf(PersistenceException.class)
                .rootCause().hasMessageContaining("value too long");
    }
}
