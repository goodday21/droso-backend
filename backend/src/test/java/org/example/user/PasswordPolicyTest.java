package org.example.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Abcdef1!",
            "Passw0rd.",
            "Z9z_____",
            "aA1~aA1~",
            "Correct-Horse-Battery-Staple-42"})
    @DisplayName("a password that follows every rule is valid")
    void validPasswords(String password) {
        assertThat(PasswordPolicy.violations(password)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"!", "\"", "#", "$", "%", "&", "'", "(", ")", "*", "+", ",", "-", ".", "/",
            ":", ";", "<", "=", ">", "?", "@", "[", "\\", "]", "^", "_", "`", "{", "|", "}", "~"})
    @DisplayName("every ASCII punctuation character counts as one")
    void punctuationCharacters(String punctuation) {
        assertThat(PasswordPolicy.violations("Abcdef1" + punctuation)).isEmpty();
    }

    @Test
    @DisplayName("the length is 8..64 characters")
    void length() {
        assertThat(PasswordPolicy.violations("Abcde1!")).containsExactly(PasswordPolicy.LENGTH);
        assertThat(PasswordPolicy.violations("Abcde1!x")).isEmpty();
        assertThat(PasswordPolicy.violations("Abcde1!" + "x".repeat(57))).isEmpty();
        assertThat(PasswordPolicy.violations("Abcde1!" + "x".repeat(58))).containsExactly(PasswordPolicy.LENGTH);
    }

    @Test
    @DisplayName("every missing kind of character is reported")
    void missingKinds() {
        assertThat(PasswordPolicy.violations("abcdef1!")).containsExactly(PasswordPolicy.UPPER_CASE);
        assertThat(PasswordPolicy.violations("ABCDEF1!")).containsExactly(PasswordPolicy.LOWER_CASE);
        assertThat(PasswordPolicy.violations("Abcdefg!")).containsExactly(PasswordPolicy.DIGIT);
        assertThat(PasswordPolicy.violations("Abcdefg1")).containsExactly(PasswordPolicy.PUNCTUATION);
    }

    @Test
    @DisplayName("an empty password violates every rule but the one of the characters")
    void empty() {
        assertThat(PasswordPolicy.violations("")).containsExactly(PasswordPolicy.LENGTH,
                PasswordPolicy.UPPER_CASE, PasswordPolicy.LOWER_CASE, PasswordPolicy.DIGIT,
                PasswordPolicy.PUNCTUATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Abcdef1! ",            // space at the end
            "Abc def1!",            // space inside
            "Abcdef1!\t",           // tab
            "Abcdef1! ",       // no-break space
            "Abcdef1!​",       // zero width space
            "Abcdef1!д",       // Cyrillic small letter de
            "Abcdef1!é",       // Latin small letter e with acute
            "Abcdef1!😀", // emoji
            "Abcdef1!\u0000",       // control character
            "Abcdef1!\u007F"})      // DELETE
    @DisplayName("any character other than printable ASCII is rejected")
    void onlyPrintableAscii(String password) {
        assertThat(PasswordPolicy.violations(password)).containsExactly(PasswordPolicy.CHARACTERS);
    }

    @Test
    @DisplayName("a Cyrillic look-alike does not count as the Latin letter it imitates")
    void lookAlikeDoesNotCount() {
        // U+0410 CYRILLIC CAPITAL LETTER A is the only upper case letter.
        assertThat(PasswordPolicy.violations("Аbcdef1!"))
                .containsExactly(PasswordPolicy.CHARACTERS, PasswordPolicy.UPPER_CASE);
    }

    @Test
    @DisplayName("an emoji counts as one character, not as two")
    void emojiCountsOnce() {
        // 7 code points, 8 UTF-16 units.
        assertThat(PasswordPolicy.violations("Abcd1!😀"))
                .containsExactly(PasswordPolicy.LENGTH, PasswordPolicy.CHARACTERS);
    }

    @Test
    @DisplayName("no message contains the password")
    void messagesDoNotContainThePassword() {
        String password = "secret";

        assertThat(PasswordPolicy.violations(password))
                .isNotEmpty()
                .allSatisfy(message -> assertThat(message).doesNotContain(password));
    }
}
