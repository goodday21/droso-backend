package org.example.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.example.support.PostgresIntegrationTest;
import org.example.support.TestUsers;
import org.example.web.error.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The locks of {@link UserManagementService} against PostgreSQL: two changes that would each be
 * allowed alone, but not together, are serialized.
 *
 * <p>Deterministic, not a race left to chance: the first change runs inside a transaction of the
 * test that stays open (its locks held) until the second change has been seen to wait for them.
 */
// A lock that is never released must fail the test, not hang the build.
@Timeout(30)
@SpringBootTest
class UserManagementLockingIntegrationTest extends PostgresIntegrationTest {

    /** Long enough for the second change to reach the lock on any machine of the CI. */
    private static final long WAIT_MILLIS = 500;

    @Autowired
    private UserManagementService service;

    @Autowired
    private UserRepository users;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Runs {@code first} in a transaction that stays open, starts {@code second} in another thread,
     * checks that it waits, then commits the first and returns the outcome of the second.
     *
     * <p>The first transaction is released in any case, a failed check included: left open, it would
     * keep its row locks, and the truncation before the next test would wait for them forever.
     */
    private CompletableFuture<Void> whileHeld(Consumer<UserManagementService> first,
            Consumer<UserManagementService> second) throws Exception {
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            first.accept(service);
            firstDone.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        CompletableFuture<Void> waiting;
        try {
            assertThat(firstDone.await(10, TimeUnit.SECONDS)).as("the first change is done").isTrue();
            waiting = CompletableFuture.runAsync(() -> second.accept(service));
            Thread.sleep(WAIT_MILLIS);
            assertThat(waiting).as("the second change waits for the locks of the first").isNotDone();
        } finally {
            release.countDown();
        }
        holder.get(10, TimeUnit.SECONDS);
        try {
            waiting.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            // Returned as a failed future, for the assertion of the caller.
        }
        return waiting;
    }

    private long activeAdmins() {
        return jdbc.queryForObject("SELECT count(*) FROM users WHERE role_code = 'ADMIN' AND deleted_at IS NULL",
                Long.class);
    }

    @Test
    @DisplayName("two administrators demoting each other at once: one succeeds, the other gets LAST_ADMIN")
    void crossDemotion() throws Exception {
        long a = TestUsers.create(users, Role.ADMIN).getId();
        long b = TestUsers.create(users, Role.ADMIN).getId();

        CompletableFuture<Void> second = whileHeld(s -> s.changeRole(b, Role.USER), s -> s.changeRole(a, Role.USER));

        assertThatThrownBy(second::join).hasCauseInstanceOf(LastAdminException.class);
        assertThat(activeAdmins()).isEqualTo(1);
    }

    @Test
    @DisplayName("an administrator deleted while another deletes the second one: exactly one remains")
    void crossDeletion() throws Exception {
        long a = TestUsers.create(users, Role.ADMIN).getId();
        long b = TestUsers.create(users, Role.ADMIN).getId();

        CompletableFuture<Void> second = whileHeld(s -> s.delete(b), s -> s.delete(a));

        assertThatThrownBy(second::join).hasCauseInstanceOf(LastAdminException.class);
        assertThat(activeAdmins()).isEqualTo(1);
    }

    @Test
    @DisplayName("a role change waiting for a deletion of the same user finds it deleted and does not revive it")
    void roleChangeAfterDeletion() throws Exception {
        // No administrator: the lock of the administrators serializes nothing, only that of the user row.
        long user = TestUsers.create(users, Role.USER).getId();

        CompletableFuture<Void> second = whileHeld(s -> s.delete(user), s -> s.changeRole(user, Role.MANAGER));

        assertThatThrownBy(second::join).hasCauseInstanceOf(ResourceNotFoundException.class);
        assertThat(jdbc.queryForMap("SELECT role_code, deleted_at, token_version FROM users WHERE id = ?", user))
                .containsEntry("role_code", "USER")
                .containsEntry("token_version", 1)
                .extractingByKey("deleted_at").isNotNull();
    }
}
