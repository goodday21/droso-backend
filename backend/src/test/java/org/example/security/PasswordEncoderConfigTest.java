package org.example.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordEncoderConfigTest {

    private final PasswordEncoder encoder = new PasswordEncoderConfig().passwordEncoder();

    @Test
    @DisplayName("a password is hashed with BCrypt of cost 12, tagged with the id of the algorithm")
    void hashFormat() {
        String hash = encoder.encode("Abcdef1!");

        assertThat(hash).startsWith("{bcrypt}$2a$12$").doesNotContain("Abcdef1!");
        // The column password_hash is VARCHAR(100).
        assertThat(hash).hasSizeLessThanOrEqualTo(100);
    }

    @Test
    @DisplayName("the hash is salted and matches the password only")
    void matches() {
        String first = encoder.encode("Abcdef1!");
        String second = encoder.encode("Abcdef1!");

        assertThat(first).isNotEqualTo(second);
        assertThat(encoder.matches("Abcdef1!", first)).isTrue();
        assertThat(encoder.matches("Abcdef1!", second)).isTrue();
        assertThat(encoder.matches("abcdef1!", first)).isFalse();
    }

    @Test
    @DisplayName("the longest valid password is hashed as a whole, within the 72 bytes of BCrypt")
    void longestPassword() {
        String longest = "Abcde1!" + "x".repeat(57);
        String hash = encoder.encode(longest);

        assertThat(encoder.matches(longest, hash)).isTrue();
        // Its last character counts.
        assertThat(encoder.matches(longest.substring(0, 63) + "y", hash)).isFalse();
    }

    @Test
    @DisplayName("a hash of an algorithm that is not configured is refused, not checked")
    void foreignAlgorithm() {
        // A plain-text "{noop}" value in the column is a fault of the data, never a valid login.
        assertThatThrownBy(() -> encoder.matches("Abcdef1!", "{noop}Abcdef1!"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
