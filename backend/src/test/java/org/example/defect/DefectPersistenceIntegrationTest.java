package org.example.defect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.example.support.PostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persists and reads {@link Defect} through JPA against a real PostgreSQL server whose schema was
 * created by the Flyway migrations.
 *
 * <p>Writing and reading happen in separate transactions, so the entity read back comes from the
 * database and not from the persistence context of the write.
 */
@SpringBootTest
class DefectPersistenceIntegrationTest extends PostgresIntegrationTest {
    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    private Defect persist(Defect defect) {
        return transaction.execute(status -> {
            entityManager.persist(defect);
            return defect;
        });
    }

    private Defect find(Long id) {
        return transaction.execute(status -> entityManager.find(Defect.class, id));
    }

    @Test
    @DisplayName("a defect with every field is persisted and read back unchanged")
    void persistsAndReadsEveryField() {
        Instant before = Instant.now();
        Defect saved = persist(new Defect("  Login fails  ", "The login form rejects valid credentials",
                "1. Open /login\n2. Enter valid credentials", "The dashboard opens",
                "\"Invalid password\" is shown", DefectPriority.HIGH));
        Instant after = Instant.now();

        assertThat(saved.getId()).isNotNull();

        Defect read = find(saved.getId());

        assertThat(read).isNotSameAs(saved);
        assertThat(read.getId()).isEqualTo(saved.getId());
        assertThat(read.getTitle()).isEqualTo("Login fails");
        assertThat(read.getDescription()).isEqualTo("The login form rejects valid credentials");
        assertThat(read.getStepsToReproduce()).isEqualTo("1. Open /login\n2. Enter valid credentials");
        assertThat(read.getExpectedResult()).isEqualTo("The dashboard opens");
        assertThat(read.getActualResult()).isEqualTo("\"Invalid password\" is shown");
        assertThat(read.getPriority()).isEqualTo(DefectPriority.HIGH);
        assertThat(read.getStatus()).isEqualTo(DefectStatus.NEW);
        assertThat(read.getCreatedAt()).isEqualTo(saved.getCreatedAt()).isBetween(before, after);
        assertThat(read.getUpdatedAt()).isEqualTo(read.getCreatedAt());
    }

    @Test
    @DisplayName("a defect with the required fields only gets the defaults and empty optional fields")
    void persistsTheDefaults() {
        Defect saved = persist(new Defect("Crash on start", null, null, null, null, null));

        Defect read = find(saved.getId());

        assertThat(read.getPriority()).isEqualTo(DefectPriority.MEDIUM);
        assertThat(read.getStatus()).isEqualTo(DefectStatus.NEW);
        assertThat(read.getDescription()).isNull();
        assertThat(read.getStepsToReproduce()).isNull();
        assertThat(read.getExpectedResult()).isNull();
        assertThat(read.getActualResult()).isNull();
        assertThat(read.getCreatedAt()).isNotNull();
        assertThat(read.getUpdatedAt()).isEqualTo(read.getCreatedAt());
    }

    @Test
    @DisplayName("every defect gets its own id from the database")
    void idsAreGenerated() {
        Long first = persist(new Defect("First defect", null, null, null, null, null)).getId();
        Long second = persist(new Defect("Second defect", null, null, null, null, null)).getId();

        assertThat(first).isNotNull();
        assertThat(second).isNotNull().isNotEqualTo(first);
    }

    @Test
    @DisplayName("the enums are stored by name and the timestamps as the same absolute moment")
    void storageFormat() {
        Defect saved = persist(new Defect("Stored by name", null, null, null, null, DefectPriority.CRITICAL));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT priority, status FROM defects WHERE id = ?", saved.getId());

        assertThat(row.get("priority")).isEqualTo("CRITICAL");
        assertThat(row.get("status")).isEqualTo("NEW");
        // Read with plain JDBC, independently of the mapping of Hibernate.
        assertThat(jdbc.queryForObject("SELECT created_at FROM defects WHERE id = ?",
                OffsetDateTime.class, saved.getId()).toInstant()).isEqualTo(saved.getCreatedAt());
        assertThat(jdbc.queryForObject("SELECT updated_at FROM defects WHERE id = ?",
                OffsetDateTime.class, saved.getId()).toInstant()).isEqualTo(saved.getUpdatedAt());
    }

    @Test
    @DisplayName("an optional field longer than its column is rejected by the database")
    void tooLongOptionalFieldIsRejected() {
        Defect defect = new Defect("Too long description", "a".repeat(2001), null, null, null, null);

        assertThatThrownBy(() -> persist(defect))
                .isInstanceOf(PersistenceException.class)
                .rootCause().hasMessageContaining("value too long");
    }
}
