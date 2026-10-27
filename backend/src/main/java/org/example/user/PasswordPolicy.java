package org.example.user;

import java.util.ArrayList;
import java.util.List;

/**
 * The rules of a password, applied when it is chosen (registration, and later the change and the
 * reset of a password); a password that is merely checked at login is not subject to them.
 *
 * <ul>
 *   <li>8..64 characters long. The upper bound keeps the password within the 72 bytes that BCrypt
 *       hashes: the encoder rejects a longer one instead of silently ignoring the rest;</li>
 *   <li>printable ASCII only ({@code !} to {@code ~}): Latin letters, digits and punctuation, no
 *       space and no other character. A letter of another alphabet or an invisible character can
 *       look like a Latin one and make the password impossible to type again;</li>
 *   <li>at least one upper case letter, one lower case letter, one digit and one punctuation
 *       character (any printable ASCII character that is neither a letter nor a digit).</li>
 * </ul>
 *
 * <p>A password is never normalized: what the user typed is what is hashed.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 64;

    // The messages are the field errors of the API. They never contain the password, and no
    // braces either, which Hibernate Validator would read as a message parameter.
    static final String LENGTH = "must be " + MIN_LENGTH + " to " + MAX_LENGTH + " characters long";
    static final String CHARACTERS = "must contain only Latin letters, digits and punctuation characters";
    static final String UPPER_CASE = "must contain an upper case letter";
    static final String LOWER_CASE = "must contain a lower case letter";
    static final String DIGIT = "must contain a digit";
    static final String PUNCTUATION = "must contain a punctuation character";

    private PasswordPolicy() {
    }

    /**
     * @return the messages of the violated rules, in a fixed order; empty if the password is valid
     */
    public static List<String> violations(String password) {
        // Code points, so that an emoji counts as one (invalid) character, not as two.
        int length = password.codePointCount(0, password.length());
        boolean printableAscii = password.chars().allMatch(c -> c >= '!' && c <= '~');
        List<String> violations = new ArrayList<>();
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            violations.add(LENGTH);
        }
        if (!printableAscii) {
            violations.add(CHARACTERS);
        }
        if (password.chars().noneMatch(c -> c >= 'A' && c <= 'Z')) {
            violations.add(UPPER_CASE);
        }
        if (password.chars().noneMatch(c -> c >= 'a' && c <= 'z')) {
            violations.add(LOWER_CASE);
        }
        if (password.chars().noneMatch(c -> c >= '0' && c <= '9')) {
            violations.add(DIGIT);
        }
        if (password.chars().noneMatch(PasswordPolicy::isPunctuation)) {
            violations.add(PUNCTUATION);
        }
        return violations;
    }

    private static boolean isPunctuation(int c) {
        return c >= '!' && c <= '~' && !(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z')
                && !(c >= '0' && c <= '9');
    }
}
