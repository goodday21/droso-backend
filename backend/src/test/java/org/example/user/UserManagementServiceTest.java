package org.example.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.example.web.error.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The rule of the last administrator and the 404 of a missing or deleted user, with a mocked
 * repository. That the locks really serialize two changes is checked against PostgreSQL by
 * {@code UserManagementLockingIntegrationTest}.
 */
class UserManagementServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    private final UserRepository repository = mock(UserRepository.class);
    private final UserManagementService service =
            new UserManagementService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    /** A stored user: registered with the mocked repository under its id. */
    private User stored(long id, Role role) {
        User user = new User("user" + id + "@example.com", "{bcrypt}hash", role);
        ReflectionTestUtils.setField(user, "id", id);
        when(repository.lockById(id)).thenReturn(Optional.of(user));
        return user;
    }

    private void activeAdmins(User... admins) {
        when(repository.lockActiveAdmins()).thenReturn(List.of(admins));
    }

    @Test
    @DisplayName("the last administrator cannot be demoted, and keeps the role")
    void lastAdminIsNotDemoted() {
        User admin = stored(1, Role.ADMIN);
        activeAdmins(admin);

        assertThatThrownBy(() -> service.changeRole(1, Role.MANAGER)).isInstanceOf(LastAdminException.class);
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("an administrator is demoted while another one remains")
    void adminDemotedWithAnother() {
        User admin = stored(1, Role.ADMIN);
        activeAdmins(admin, stored(2, Role.ADMIN));

        assertThat(service.changeRole(1, Role.USER).role()).isEqualTo(Role.USER);
        assertThat(admin.getRole()).isEqualTo(Role.USER);
        assertThat(admin.getTokenVersion()).isZero();
    }

    @Test
    @DisplayName("the last administrator may be given the role ADMIN again (no change)")
    void lastAdminKeepsAdmin() {
        User admin = stored(1, Role.ADMIN);
        activeAdmins(admin);

        assertThat(service.changeRole(1, Role.ADMIN).role()).isEqualTo(Role.ADMIN);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    @DisplayName("a user who is not an administrator gets any role, even without an administrator at all")
    void nonAdminGetsAnyRole(Role role) {
        User user = stored(3, Role.USER);
        activeAdmins();

        assertThat(service.changeRole(3, role).role()).isEqualTo(role);
        assertThat(user.getRole()).isEqualTo(role);
    }

    @Test
    @DisplayName("the last administrator cannot be deleted")
    void lastAdminIsNotDeleted() {
        User admin = stored(1, Role.ADMIN);
        activeAdmins(admin);

        assertThatThrownBy(() -> service.delete(1)).isInstanceOf(LastAdminException.class);
        assertThat(admin.isActive()).isTrue();
        assertThat(admin.getTokenVersion()).isZero();
    }

    @Test
    @DisplayName("a deletion sets the moment of the clock and revokes the tokens")
    void delete() {
        User admin = stored(1, Role.ADMIN);
        activeAdmins(admin, stored(2, Role.ADMIN));

        service.delete(1);

        assertThat(admin.getDeletedAt()).isEqualTo(NOW);
        assertThat(admin.getTokenVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("a missing or deleted user is not found, for a role change and for a deletion")
    void missingOrDeleted() {
        when(repository.lockById(9L)).thenReturn(Optional.empty());
        stored(4, Role.USER).delete(NOW);

        assertThatThrownBy(() -> service.changeRole(9, Role.USER)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(9)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.changeRole(4, Role.USER)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(4)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("the list asks for the requested page of the active users, sorted by id")
    void list() {
        when(repository.findByDeletedAtIsNull(org.mockito.ArgumentMatchers.any())).thenReturn(Page.empty());

        service.list(2, 50);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByDeletedAtIsNull(pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by("id"));
    }
}
