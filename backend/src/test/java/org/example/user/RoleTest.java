package org.example.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.example.user.Permission.TICKET_CREATE;
import static org.example.user.Permission.TICKET_READ;
import static org.example.user.Permission.TICKET_UPDATE;
import static org.example.user.Permission.USER_MANAGE;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoleTest {

    @Test
    @DisplayName("each role has exactly the permissions of the agreed matrix")
    void matrix() {
        assertThat(Role.USER.permissions()).containsExactly(TICKET_READ, TICKET_CREATE);
        assertThat(Role.MANAGER.permissions()).containsExactly(TICKET_READ, TICKET_CREATE, TICKET_UPDATE);
        assertThat(Role.ADMIN.permissions()).containsExactly(TICKET_READ, TICKET_CREATE, TICKET_UPDATE, USER_MANAGE);
    }

    @Test
    @DisplayName("the roles are nested: a manager may do all a user may, an admin all a manager may")
    void nested() {
        assertThat(Role.MANAGER.permissions()).containsAll(Role.USER.permissions());
        assertThat(Role.ADMIN.permissions()).containsAll(Role.MANAGER.permissions());
    }

    @Test
    @DisplayName("every permission is granted to some role, and only the admin manages the users")
    void everyPermissionIsUsed() {
        assertThat(Role.ADMIN.permissions()).containsExactlyInAnyOrder(Permission.values());
        assertThat(Role.MANAGER.permissions()).doesNotContain(USER_MANAGE);
    }

    @Test
    @DisplayName("the permissions of a role cannot be changed")
    void unmodifiable() {
        assertThatThrownBy(() -> Role.USER.permissions().add(USER_MANAGE))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
