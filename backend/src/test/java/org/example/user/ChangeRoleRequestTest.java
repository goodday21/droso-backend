package org.example.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.example.user.model.ChangeRoleRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The constraint of {@link ChangeRoleRequest}, without Spring. Unknown names and indexes are
 * rejected by Jackson before validation ({@code UserManagementIntegrationTest}).
 */
class ChangeRoleRequestTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    @DisplayName("every role is accepted")
    void everyRole(Role role) {
        assertThat(VALIDATOR.validate(new ChangeRoleRequest(role))).isEmpty();
    }

    @Test
    @DisplayName("the role is required")
    void roleRequired() {
        assertThat(VALIDATOR.validate(new ChangeRoleRequest(null)))
                .extracting(violation -> violation.getPropertyPath().toString(), ConstraintViolation::getMessage)
                .containsExactly(tuple("role", "must not be null"));
    }
}
