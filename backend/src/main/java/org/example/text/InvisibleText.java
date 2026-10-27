package org.example.text;

/**
 * Removes the characters a user cannot see, so that a text made of them only counts as empty.
 *
 * <p>{@link String#strip()} and {@code @NotBlank} know {@link Character#isWhitespace} only, which
 * deliberately excludes the no-break spaces (U+00A0, U+2007, U+202F) and knows nothing of the
 * zero-width characters: a title of five no-break spaces would pass both as five characters.
 *
 * <p>Invisible here are the whitespace, every space separator ({@link Character#isSpaceChar}) and
 * a fixed list of zero-width characters. Not the whole Unicode category FORMAT: it also holds the
 * tag characters that end a flag emoji such as the one of England, which stripping would break.
 */
public final class InvisibleText {

    private InvisibleText() {
    }

    /**
     * @return the text without the invisible characters at both ends, the ones inside are kept;
     *         {@code null} for {@code null}
     */
    public static String strip(String text) {
        if (text == null) {
            return null;
        }
        int start = 0;
        while (start < text.length() && isInvisible(text.codePointAt(start))) {
            start += Character.charCount(text.codePointAt(start));
        }
        int end = text.length();
        while (end > start && isInvisible(text.codePointBefore(end))) {
            end -= Character.charCount(text.codePointBefore(end));
        }
        return text.substring(start, end);
    }

    /**
     * @return {@code null} if the text is {@code null} or invisible as a whole, otherwise the text
     *         unchanged, its surrounding whitespace included
     */
    public static String blankToNull(String text) {
        return text == null || strip(text).isEmpty() ? null : text;
    }

    static boolean isInvisible(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || switch (codePoint) {
            // NEXT LINE, MONGOLIAN VOWEL SEPARATOR, ZERO WIDTH SPACE, ZERO WIDTH NON-JOINER,
            // ZERO WIDTH JOINER, WORD JOINER, ZERO WIDTH NO-BREAK SPACE (the BOM)
            case 0x0085, 0x180E, 0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF -> true;
            default -> false;
        };
    }
}
