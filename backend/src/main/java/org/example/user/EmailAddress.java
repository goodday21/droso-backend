package org.example.user;

import java.util.Locale;
import java.util.regex.Pattern;

import org.example.text.InvisibleText;

/**
 * The rules of the email address that serves as the login.
 *
 * <p>An address is stored {@linkplain #normalize normalized}, so that the case and the surrounding
 * invisible characters never make two accounts of one address. The part after a {@code +} is kept:
 * {@code qwe+1@gmail.com} and {@code qwe@gmail.com} are two accounts.
 *
 * <p>{@code @Email} of Hibernate Validator is deliberately lax (it accepts {@code a@b} and the
 * quoted local parts of RFC 5322); the rules here are the ones of the addresses in actual use:
 * <ul>
 *   <li>the local part is made of {@code a-z 0-9 . _ % + -}, at most 64 characters, with no dot
 *       at its start, at its end or twice in a row;</li>
 *   <li>the domain is made of at least two labels of {@code a-z 0-9 -}, each 1..63 characters
 *       long and neither starting nor ending with a hyphen; the last label (the top-level domain)
 *       is made of at least two letters;</li>
 *   <li>the whole address is at most 254 characters long.</li>
 * </ul>
 * Internationalized addresses (non-ASCII local parts or domains) are not accepted yet.
 */
public final class EmailAddress {

    public static final int MAX_LENGTH = 254;
    static final int LOCAL_PART_MAX_LENGTH = 64;

    // Matched after normalization, so lower case only. Neither pattern nests quantifiers, so the
    // time of a match grows linearly with the length, which is bounded before matching anyway.
    private static final Pattern LOCAL_PART = Pattern.compile("[a-z0-9_%+-]+(\\.[a-z0-9_%+-]+)*");
    private static final Pattern DOMAIN = Pattern.compile(
            "([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");

    private EmailAddress() {
    }

    /**
     * @return the address without the surrounding invisible characters ({@link InvisibleText}),
     *         in lower case; {@code null} for {@code null}
     */
    public static String normalize(String email) {
        return email == null ? null : InvisibleText.strip(email).toLowerCase(Locale.ROOT);
    }

    /**
     * @param email a {@linkplain #normalize normalized} address
     * @return whether it follows the rules of this class; {@code false} for {@code null}
     */
    public static boolean isValid(String email) {
        if (email == null || email.length() > MAX_LENGTH) {
            return false;
        }
        int at = email.indexOf('@');
        if (at < 0 || at != email.lastIndexOf('@')) {
            return false;
        }
        String localPart = email.substring(0, at);
        return localPart.length() <= LOCAL_PART_MAX_LENGTH
                && LOCAL_PART.matcher(localPart).matches()
                && DOMAIN.matcher(email.substring(at + 1)).matches();
    }
}
