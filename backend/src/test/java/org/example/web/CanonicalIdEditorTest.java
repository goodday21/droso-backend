package org.example.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CanonicalIdEditorTest {

    @ParameterizedTest
    @ValueSource(strings = {"1", "9", "10", "26", "1000000", "9223372036854775807"})
    @DisplayName("a positive decimal number without sign or leading zeros is read")
    void readsCanonicalIds(String text) {
        CanonicalIdEditor editor = new CanonicalIdEditor();

        editor.setAsText(text);

        assertThat(editor.getValue()).isEqualTo(Long.parseLong(text));
        assertThat(editor.getAsText()).isEqualTo(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", " ", "0", "00", "-1", "+1", "01", "026", " 1", "1 ", "1\n", "\t1",
            "0x1A", "0X1A", "#1A", "-0x1", "1A", "abc", "1.5", "1e3", "1_000", "1,5",
            "١", "１",                                  // Arabic-Indic and fullwidth digits
            "9223372036854775808", "99999999999999999999"})
    @DisplayName("anything else, including what Long.decode or Long.parseLong would accept, is rejected")
    void rejectsOtherValues(String text) {
        CanonicalIdEditor editor = new CanonicalIdEditor();

        assertThatIllegalArgumentException().isThrownBy(() -> editor.setAsText(text));
        assertThat(editor.getValue()).isNull();
    }

    @Test
    @DisplayName("null is rejected")
    void rejectsNull() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CanonicalIdEditor().setAsText(null));
    }
}
