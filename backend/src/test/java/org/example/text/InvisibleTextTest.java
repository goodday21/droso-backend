package org.example.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InvisibleTextTest {

    /** The flag of England: a black flag, five tag characters and CANCEL TAG (category FORMAT). */
    private static final String FLAG_OF_ENGLAND = "🏴󠁧󠁢󠁥"
            + "󠁮󠁧󠁿";

    /** Woman technologist: WOMAN, ZERO WIDTH JOINER, PERSONAL COMPUTER. */
    private static final String WOMAN_TECHNOLOGIST = "👩‍💻";

    @ParameterizedTest
    @ValueSource(strings = {
            " ", "\t", "\n", "\r", "\u000B", "\f", "\u0085", " ", " ", "᠎", " ",
            " ", " ", "​", "‌", "‍", " ", " ", " ", " ",
            "⁠", "　", "﻿"})
    @DisplayName("whitespace, space separators and zero-width characters are invisible")
    void invisibleCharacters(String character) {
        assertThat(InvisibleText.strip(character + "abc" + character)).isEqualTo("abc");
        assertThat(InvisibleText.blankToNull(character.repeat(3))).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "1", ".", "­", "‎", "️"})
    @DisplayName("visible characters and other format characters are kept")
    void visibleCharacters(String character) {
        assertThat(InvisibleText.strip(character)).isEqualTo(character);
    }

    @Test
    @DisplayName("the invisible characters inside the text are kept")
    void insideIsKept() {
        assertThat(InvisibleText.strip(" Login ​fails﻿")).isEqualTo("Login ​fails");
    }

    @Test
    @DisplayName("an emoji sequence at either end of the text stays intact")
    void emojiSequencesAreKept() {
        assertThat(InvisibleText.strip(" " + FLAG_OF_ENGLAND + " Bug " + FLAG_OF_ENGLAND + " "))
                .isEqualTo(FLAG_OF_ENGLAND + " Bug " + FLAG_OF_ENGLAND);
        assertThat(InvisibleText.strip(WOMAN_TECHNOLOGIST)).isEqualTo(WOMAN_TECHNOLOGIST);
    }

    @Test
    @DisplayName("null stays null, an invisible text becomes empty")
    void nullAndEmpty() {
        assertThat(InvisibleText.strip(null)).isNull();
        assertThat(InvisibleText.strip("")).isEmpty();
        assertThat(InvisibleText.strip(" ​ ﻿")).isEmpty();
    }

    @Test
    @DisplayName("blankToNull keeps a visible text unchanged, its surrounding whitespace included")
    void blankToNull() {
        assertThat(InvisibleText.blankToNull(null)).isNull();
        assertThat(InvisibleText.blankToNull("")).isNull();
        assertThat(InvisibleText.blankToNull("  text\n")).isEqualTo("  text\n");
    }
}
