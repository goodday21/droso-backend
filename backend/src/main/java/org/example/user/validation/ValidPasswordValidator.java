package org.example.user.validation;

import java.util.List;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.example.user.PasswordPolicy;

public class ValidPasswordValidator implements ConstraintValidator<ValidPassword, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        List<String> violations = PasswordPolicy.violations(value);
        if (violations.isEmpty()) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        // The messages are fixed texts without braces or "$", so they are safe as templates; the
        // password itself never becomes part of a template, which could otherwise be interpolated.
        violations.forEach(message -> context.buildConstraintViolationWithTemplate(message).addConstraintViolation());
        return false;
    }
}
