package org.example.user.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import org.example.user.EmailAddress;

/**
 * The value is an email address that follows the rules of {@link EmailAddress}. It is checked as
 * given, so a request DTO normalizes it first. {@code null} and the empty string are valid here:
 * they are the concern of {@code @NotBlank}, which would otherwise report them twice.
 */
@Documented
@Constraint(validatedBy = ValidEmailValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidEmail {

    String message() default "must be a well-formed email address";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
