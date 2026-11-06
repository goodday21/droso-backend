package org.example.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.example.ticket.model.CreateTicketRequest;
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
 * The {@code @PreAuthorize} of {@link TicketController} is enforced in the real application context.
 *
 * <p>Every role has the permissions of the tickets, so no stored user can be refused through HTTP.
 * The controller bean is therefore called directly with an authentication that lacks the
 * permission: what is checked is that method security is on and the methods name the right
 * permission. How a refusal is answered (403 as {@code ApiError}) is checked by
 * {@code ApiExceptionHandlerTest}; which role has which permission by {@code RoleTest}.
 */
@SpringBootTest
class TicketPermissionIntegrationTest extends PostgresIntegrationTest {

    private static final CreateTicketRequest REQUEST =
            new CreateTicketRequest("Login fails", null, null, null, null, null, null);

    @Autowired
    private TicketController controller;

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
    @DisplayName("creating a ticket is refused without TICKET_CREATE, even with every other permission, and stores nothing")
    void createNeedsTicketCreate() {
        AuthenticatedUser user = authenticate(Permission.TICKET_READ, Permission.TICKET_UPDATE, Permission.USER_MANAGE);

        assertThatThrownBy(() -> controller.create(REQUEST, user)).isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tickets", Long.class)).isZero();
    }

    @Test
    @DisplayName("creating a ticket with TICKET_CREATE records the user as its author")
    void createWithTicketCreate() {
        AuthenticatedUser user = authenticate(Permission.TICKET_CREATE);

        assertThat(controller.create(REQUEST, user).getBody().createdBy()).isEqualTo(user.id());
    }

    @Test
    @DisplayName("reading a ticket is refused without TICKET_READ, before the ticket is looked up")
    void getNeedsTicketRead() {
        authenticate(Permission.TICKET_CREATE, Permission.TICKET_UPDATE, Permission.USER_MANAGE);

        assertThatThrownBy(() -> controller.get(1L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("reading a ticket with TICKET_READ passes the check")
    void getWithTicketRead() {
        authenticate(Permission.TICKET_READ);

        // No ticket is stored: reaching the lookup proves the check let the call through.
        assertThatThrownBy(() -> controller.get(1L)).isInstanceOf(ResourceNotFoundException.class);
    }
}
