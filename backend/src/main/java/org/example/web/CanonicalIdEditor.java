package org.example.web;

import java.beans.PropertyEditorSupport;
import java.util.regex.Pattern;

/**
 * Reads a {@code Long} id of a URL in its canonical form only: a positive decimal number without
 * sign, leading zeros or whitespace, e.g. {@code 26}.
 *
 * <p>The default conversion of Spring ({@code NumberUtils.parseNumber}) is lenient: it strips the
 * whitespace and decodes hexadecimal numbers, so {@code /defects/0x1A}, {@code /defects/%231A},
 * {@code /defects/+26}, {@code /defects/026} and {@code /defects/%2026} would all be the defect 26.
 * Such aliases make one resource appear under many URLs (caches, logs, future access rules) and
 * turn a client error into a hit on another resource. Here they are rejected instead.
 *
 * <p>A rejected value surfaces as a {@code TypeMismatchException}, which {@code ApiExceptionHandler}
 * answers with {@code 400 VALIDATION_ERROR} and {@code "has an invalid value"} for the parameter.
 * Register it per controller with {@code @InitBinder}; an instance is not thread-safe, so a new
 * one is created for every binder.
 */
public class CanonicalIdEditor extends PropertyEditorSupport {

    /** ASCII digits only: {@code Long.parseLong} would also accept, e.g., Arabic-Indic digits. */
    private static final Pattern CANONICAL = Pattern.compile("[1-9][0-9]{0,18}");

    @Override
    public void setAsText(String text) {
        if (text == null || !CANONICAL.matcher(text).matches()) {
            throw new IllegalArgumentException("Not a canonical id");
        }
        try {
            setValue(Long.parseLong(text));
        } catch (NumberFormatException e) {
            // Nineteen digits may still exceed Long.MAX_VALUE.
            throw new IllegalArgumentException("Id out of range", e);
        }
    }

    @Override
    public String getAsText() {
        Object value = getValue();
        return value == null ? "" : value.toString();
    }
}
