package org.example.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;

/**
 * The order of each field. That PostgreSQL applies it as intended (the collation of the email) is
 * checked by {@code UserManagementIntegrationTest}.
 */
class UserSortTest {

    @ParameterizedTest
    @EnumSource(Direction.class)
    @DisplayName("the id is the whole order")
    void byId(Direction direction) {
        assertThat(UserSort.ID.toSort(direction)).isEqualTo(Sort.by(direction, "id"));
    }

    @ParameterizedTest
    @EnumSource(Direction.class)
    @DisplayName("the email is followed by the id in the same direction")
    void byEmail(Direction direction) {
        assertThat(UserSort.EMAIL.toSort(direction)).isEqualTo(Sort.by(direction, "email", "id"));
    }

    @ParameterizedTest
    @EnumSource(Direction.class)
    @DisplayName("the role is followed by the id in the same direction, so that equal roles keep one order")
    void byRole(Direction direction) {
        assertThat(UserSort.ROLE.toSort(direction)).isEqualTo(Sort.by(direction, "role", "id"));
    }
}
