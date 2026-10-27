package org.example.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserTest {

    private static final String HASH = "{bcrypt}$2a$12$hash";

    @Test
    @DisplayName("a new user is active and has the normalized email and the given role")
    void newUser() {
        User user = new User(" Qwe.Qwe+1@GMail.com ", HASH, Role.USER);

        assertThat(user.getEmail()).isEqualTo("qwe.qwe+1@gmail.com");
        assertThat(user.getRole()).isEqualTo(Role.USER);
        assertThat(user.getDeletedAt()).isNull();
        assertThat(user.getId()).isNull();
    }

    @Test
    @DisplayName("an invalid email is rejected without repeating it")
    void invalidEmail() {
        assertThatThrownBy(() -> new User("secret@b", HASH, Role.USER))
                .isInstanceOf(IllegalArgumentException.class)
                .message().doesNotContain("secret");
        assertThatThrownBy(() -> new User(null, HASH, Role.USER)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the password hash and the role are required")
    void requiredArguments() {
        assertThatThrownBy(() -> new User("a@b.cd", null, Role.USER)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new User("a@b.cd", "", Role.USER)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new User("a@b.cd", HASH, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the timestamps are equal on creation, the update time moves on a change")
    void timestamps() throws InterruptedException {
        User user = new User("a@b.cd", HASH, Role.USER);

        user.initializeTimestamps();
        assertThat(user.getUpdatedAt()).isEqualTo(user.getCreatedAt());
        assertThat(user.getCreatedAt().getNano() % 1000).isZero();

        Thread.sleep(2);
        user.refreshUpdatedAt();
        assertThat(user.getUpdatedAt()).isAfter(user.getCreatedAt());
        assertThat(user.getUpdatedAt().getNano() % 1000).isZero();
    }
}
