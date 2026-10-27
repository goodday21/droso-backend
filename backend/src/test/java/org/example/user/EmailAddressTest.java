package org.example.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmailAddressTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "qwe.qwe+1@gmail.com",
            "qwe@gmail.com",
            "a@b.cd",
            "first.middle.last@sub.example.co.uk",
            "user_name%tag-1@my-domain.example",
            "1234567890@example.org",
            "-dash-@example.org",
            "x@xn--80ak6aa92e.com"})
    @DisplayName("addresses in actual use are valid, the part after a plus included")
    void validAddresses(String email) {
        assertThat(EmailAddress.isValid(email)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "plainaddress",
            "@example.com",
            "user@",
            "user@@example.com",
            "user@host@example.com",
            "a@b",
            "user@localhost",
            ".user@example.com",
            "user.@example.com",
            "us..er@example.com",
            "user@.example.com",
            "user@example..com",
            "user@example.com.",
            "user@-example.com",
            "user@example-.com",
            "user@example.c",
            "user@example.123",
            "user@exa_mple.com",
            "us er@example.com",
            "\"quoted\"@example.com",
            "user(comment)@example.com",
            "user@[192.168.0.1]",
            "пользователь@example.com",
            "user@пример.рф",
            "User@Example.com"})
    @DisplayName("malformed, exotic, internationalized and not normalized addresses are invalid")
    void invalidAddresses(String email) {
        assertThat(EmailAddress.isValid(email)).isFalse();
    }

    @Test
    @DisplayName("null is invalid")
    void nullIsInvalid() {
        assertThat(EmailAddress.isValid(null)).isFalse();
    }

    @Test
    @DisplayName("the local part is at most 64 characters long")
    void localPartLength() {
        assertThat(EmailAddress.isValid("a".repeat(64) + "@example.com")).isTrue();
        assertThat(EmailAddress.isValid("a".repeat(65) + "@example.com")).isFalse();
    }

    @Test
    @DisplayName("a domain label is at most 63 characters long")
    void labelLength() {
        assertThat(EmailAddress.isValid("user@" + "a".repeat(63) + ".com")).isTrue();
        assertThat(EmailAddress.isValid("user@" + "a".repeat(64) + ".com")).isFalse();
    }

    @Test
    @DisplayName("the whole address is at most 254 characters long")
    void totalLength() {
        // 64 + 1 + 189 = 254, with every label within its own limit.
        String domain = "a".repeat(61) + "." + "b".repeat(61) + "." + "c".repeat(61) + ".com";
        String longest = "x".repeat(64) + "@" + domain;
        assertThat(longest).hasSize(254);

        assertThat(EmailAddress.isValid(longest)).isTrue();
        assertThat(EmailAddress.isValid(longest.replace("@", "@d"))).isFalse();
    }

    @Test
    @DisplayName("a very long input is rejected without matching it")
    void longInput() {
        assertThat(EmailAddress.isValid("a".repeat(1_000_000) + "@example.com")).isFalse();
    }

    @Test
    @DisplayName("normalization strips the invisible characters and lowers the case, the plus part stays")
    void normalize() {
        assertThat(EmailAddress.normalize("  Qwe.QWE+Tag@GMail.COM​\t")).isEqualTo("qwe.qwe+tag@gmail.com");
        assertThat(EmailAddress.normalize(null)).isNull();
    }

    @Test
    @DisplayName("normalization does not depend on the default locale")
    void normalizeIgnoresLocale() {
        // In a Turkish locale "I".toLowerCase() is a dotless i, which would make the address invalid.
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertThat(EmailAddress.normalize("IVAN@EXAMPLE.COM")).isEqualTo("ivan@example.com");
        } finally {
            Locale.setDefault(previous);
        }
    }
}
