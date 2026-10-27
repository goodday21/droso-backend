package org.example.user.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import org.example.user.PasswordPolicy;

/**
 * The value is a password that follows the rules of {@link PasswordPolicy}. Every violated rule is
 * reported with a message of its own, so that the client can show all of them at once. {@code null}
 * is valid here: it is the concern of {@code @NotNull}.
 */
@Documented
@Constraint(validatedBy = ValidPasswordValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPassword {

    /** Unused: the messages are the ones of the violated rules. */
    String message() default "is not a valid password";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
