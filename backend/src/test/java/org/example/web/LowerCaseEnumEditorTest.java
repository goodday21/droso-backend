package org.example.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Sort.Direction;

class LowerCaseEnumEditorTest {

    @ParameterizedTest
    @EnumSource(Direction.class)
    @DisplayName("the name of a constant in lower case is read, and written back the same way")
    void readsLowerCaseNames(Direction direction) {
        LowerCaseEnumEditor<Direction> editor = new LowerCaseEnumEditor<>(Direction.class);
        String text = direction.name().toLowerCase(Locale.ROOT);

        editor.setAsText(text);

        assertThat(editor.getValue()).isEqualTo(direction);
        assertThat(editor.getAsText()).isEqualTo(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "ASC", "Asc", "aSc", " asc", "asc ", "asc\n", "ascending", "up", "0", "1"})
    @DisplayName("any other spelling, including what Enum.valueOf or Sort.Direction.fromString would accept, is rejected")
    void rejectsOtherSpellings(String text) {
        LowerCaseEnumEditor<Direction> editor = new LowerCaseEnumEditor<>(Direction.class);

        assertThatIllegalArgumentException().isThrownBy(() -> editor.setAsText(text));
        assertThat(editor.getValue()).isNull();
    }

    @Test
    @DisplayName("null is rejected, and no value is written as an empty text")
    void rejectsNull() {
        LowerCaseEnumEditor<Direction> editor = new LowerCaseEnumEditor<>(Direction.class);

        assertThatIllegalArgumentException().isThrownBy(() -> editor.setAsText(null));
        assertThat(editor.getAsText()).isEmpty();
    }
}
