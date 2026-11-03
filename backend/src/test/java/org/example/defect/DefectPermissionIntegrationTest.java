package org.example.defect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.example.defect.model.CreateDefectRequest;
import org.example.security.AuthenticatedUser;
import org.example.security.UserAuthentication;
import org.example.support.PostgresIntegrationTest;
import org.example.support.TestUsers;
import org.example.user.Permission;
import org.example.user.Role;
import org.example.user.User;
import org.example.user.UserRepository;
import org.example.web.error.ResourceNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The {@code @PreAuthorize} of {@link DefectController} is enforced in the real application context.
 *
 * <p>Every role has the permissions of the defects, so no stored user can be refused through HTTP.
 * The controller bean is therefore called directly with an authentication that lacks the
 * permission: what is checked is that method security is on and the methods name the right
 * permission. How a refusal is answered (403 as {@code ApiError}) is checked by
 * {@code ApiExceptionHandlerTest}; which role has which permission by {@code RoleTest}.
 */
@SpringBootTest
class DefectPermissionIntegrationTest extends PostgresIntegrationTest {

    private static final CreateDefectRequest REQUEST =
            new CreateDefectRequest("Login fails", null, null, null, null, null, null);

    @Autowired
    private DefectController controller;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    /** Authenticates the current thread as a stored user with exactly these permissions. */
    private AuthenticatedUser authenticate(Permission... permissions) {
        User user = TestUsers.create(users, Role.USER);
        AuthenticatedUser principal = AuthenticatedUser.of(user);
        Jwt token = Jwt.withTokenValue("token").header("alg", "HS256").subject(String.valueOf(user.getId()))
                .issuedAt(Instant.now()).build();
        List<SimpleGrantedAuthority> authorities = Arrays.stream(permissions)
                .map(permission -> new SimpleGrantedAuthority(permission.name())).toList();
        SecurityContextHolder.getContext().setAuthentication(new UserAuthentication(principal, token, authorities));
        return principal;
    }

    @Test
    @DisplayName("creating a defect is refused without DEFECT_CREATE, even with every other permission, and stores nothing")
    void createNeedsDefectCreate() {
        AuthenticatedUser user = authenticate(Permission.DEFECT_READ, Permission.DEFECT_UPDATE, Permission.USER_MANAGE);

        assertThatThrownBy(() -> controller.create(REQUEST, user)).isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM defects", Long.class)).isZero();
    }

    @Test
    @DisplayName("creating a defect with DEFECT_CREATE records the user as its author")
    void createWithDefectCreate() {
        AuthenticatedUser user = authenticate(Permission.DEFECT_CREATE);

        assertThat(controller.create(REQUEST, user).getBody().createdBy()).isEqualTo(user.id());
    }

    @Test
    @DisplayName("reading a defect is refused without DEFECT_READ, before the defect is looked up")
    void getNeedsDefectRead() {
        authenticate(Permission.DEFECT_CREATE, Permission.DEFECT_UPDATE, Permission.USER_MANAGE);

        assertThatThrownBy(() -> controller.get(1L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("reading a defect with DEFECT_READ passes the check")
    void getWithDefectRead() {
        authenticate(Permission.DEFECT_READ);

        // No defect is stored: reaching the lookup proves the check let the call through.
        assertThatThrownBy(() -> controller.get(1L)).isInstanceOf(ResourceNotFoundException.class);
    }
}
