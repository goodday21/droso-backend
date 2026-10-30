package org.example.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;

import org.example.bootstrap.BootstrapAdminService.Outcome;
import org.example.user.Role;
import org.example.user.User;
import org.example.user.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Every branch of the start-up check, with a mocked repository. What the database does with the
 * administrator (the unique index, the hash, the login) is checked by
 * {@code BootstrapAdminIntegrationTest}.
 */
class BootstrapAdminServiceTest {

    private static final String EMAIL = "admin@example.com";
    private static final String PASSWORD = "Adm1n-Secret!";

    private final UserRepository repository = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final BootstrapAdminService service = new BootstrapAdminService(repository, encoder);

    @BeforeEach
    void encoder() {
        when(encoder.encode(PASSWORD)).thenReturn("{bcrypt}hash");
    }

    private static BootstrapAdminProperties settings(String email, String password) {
        return new BootstrapAdminProperties(email, password);
    }

    private static DataIntegrityViolationException violation(String constraint) {
        return new DataIntegrityViolationException("duplicate",
                new ConstraintViolationException("duplicate", new SQLException("duplicate"), constraint));
    }

    @Test
    @DisplayName("without an administrator, the configured one is created with the role ADMIN and the encoded password")
    void createsTheAdmin() {
        assertThat(service.bootstrap(settings(" Admin@Example.com ", PASSWORD))).isEqualTo(Outcome.CREATED);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo(EMAIL);
        assertThat(saved.getValue().getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("{bcrypt}hash");
    }

    @Test
    @DisplayName("with an active administrator, nothing is checked or created, even with invalid settings")
    void adminExists() {
        when(repository.existsByRoleAndDeletedAtIsNull(Role.ADMIN)).thenReturn(true);

        assertThat(service.bootstrap(settings("not an email", "weak"))).isEqualTo(Outcome.ADMIN_EXISTS);
        assertThat(service.bootstrap(settings(null, null))).isEqualTo(Outcome.ADMIN_EXISTS);

        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(encoder);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"null, null", "'', ''", "'  ', '  '"})
    @DisplayName("without an administrator and without settings, the start-up goes on and creates nothing")
    void notConfigured(String email, String password) {
        assertThat(service.bootstrap(settings(email, password))).isEqualTo(Outcome.NOT_CONFIGURED);

        verify(repository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"admin@example.com, null", "null, Adm1n-Secret!", "admin@example.com, '  '"})
    @DisplayName("only one of the two settings stops the start-up")
    void incompleteSettings(String email, String password) {
        assertThatThrownBy(() -> service.bootstrap(settings(email, password)))
                .isInstanceOf(BootstrapAdminException.class)
                .hasMessage("DROSO_BOOTSTRAP_ADMIN_EMAIL and DROSO_BOOTSTRAP_ADMIN_PASSWORD must be set together");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("an invalid email stops the start-up without repeating the address")
    void invalidEmail() {
        assertThatThrownBy(() -> service.bootstrap(settings("admin@localhost", PASSWORD)))
                .isInstanceOf(BootstrapAdminException.class)
                .hasMessage("DROSO_BOOTSTRAP_ADMIN_EMAIL is not a valid email address");
        verifyNoInteractions(encoder);
    }

    @Test
    @DisplayName("a password against the policy stops the start-up, naming the rules but not the password")
    void weakPassword() {
        assertThatThrownBy(() -> service.bootstrap(settings(EMAIL, "adminadmin")))
                .isInstanceOf(BootstrapAdminException.class)
                .hasMessageStartingWith("DROSO_BOOTSTRAP_ADMIN_PASSWORD violates the password policy: ")
                .hasMessageContaining("must contain an upper case letter")
                .message().doesNotContain("adminadmin");
        verifyNoInteractions(encoder);
    }

    @Test
    @DisplayName("an email of an existing user stops the start-up instead of making that user an administrator")
    void emailTaken() {
        when(repository.saveAndFlush(any())).thenThrow(violation(UserRepository.EMAIL_ACTIVE_KEY));

        assertThatThrownBy(() -> service.bootstrap(settings(EMAIL, PASSWORD)))
                .isInstanceOf(BootstrapAdminException.class)
                .hasMessageStartingWith("DROSO_BOOTSTRAP_ADMIN_EMAIL is the email of an existing user")
                .message().doesNotContain(EMAIL);
    }

    @Test
    @DisplayName("losing the race to another instance that created the administrator is not an error")
    void concurrentInstance() {
        when(repository.existsByRoleAndDeletedAtIsNull(Role.ADMIN)).thenReturn(false, true);
        when(repository.saveAndFlush(any())).thenThrow(violation(UserRepository.EMAIL_ACTIVE_KEY));

        assertThat(service.bootstrap(settings(EMAIL, PASSWORD))).isEqualTo(Outcome.ADMIN_EXISTS);
    }

    @Test
    @DisplayName("any other failure of the insert is passed on unchanged")
    void otherFailure() {
        DataIntegrityViolationException failure = violation("some_other_check");
        when(repository.saveAndFlush(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.bootstrap(settings(EMAIL, PASSWORD))).isSameAs(failure);
    }

    @Test
    @DisplayName("the settings print neither the email nor the password")
    void toStringHidesTheSettings() {
        assertThat(settings(EMAIL, PASSWORD).toString())
                .isEqualTo("BootstrapAdminProperties[email=***, password=***]");
        assertThat(settings(null, "").toString())
                .isEqualTo("BootstrapAdminProperties[email=<not set>, password=<not set>]");
    }
}
