package org.example.web;

import java.beans.PropertyEditorSupport;
import java.util.Locale;

/**
 * Reads a constant of an enum from a query parameter written exactly as its name in lower case,
 * e.g. {@code email} for {@code EMAIL}, as the properties of the JSON bodies are spelled.
 *
 * <p>The default conversion of Spring ({@code Enum.valueOf} after a trim) wants the upper-case name
 * and accepts surrounding whitespace, and {@code Sort.Direction.fromString} ignores the case
 * altogether. Here one spelling is accepted per value, so that one list has one URL, as with the
 * ids ({@link CanonicalIdEditor}).
 *
 * <p>A rejected value surfaces as a {@code TypeMismatchException}, which {@code ApiExceptionHandler}
 * answers with {@code 400 VALIDATION_ERROR} and {@code "has an invalid value"} for the parameter.
 * Register it per controller with {@code @InitBinder}; an instance is not thread-safe, so a new
 * one is created for every binder.
 */
public class LowerCaseEnumEditor<E extends Enum<E>> extends PropertyEditorSupport {

    private final Class<E> type;

    public LowerCaseEnumEditor(Class<E> type) {
        this.type = type;
    }

    @Override
    public void setAsText(String text) {
        for (E constant : type.getEnumConstants()) {
            if (spelling(constant).equals(text)) {
                setValue(constant);
                return;
            }
        }
        throw new IllegalArgumentException("Not a value of " + type.getSimpleName());
    }

    @Override
    public String getAsText() {
        Object value = getValue();
        return value == null ? "" : spelling(type.cast(value));
    }

    private static String spelling(Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT);
    }
}
