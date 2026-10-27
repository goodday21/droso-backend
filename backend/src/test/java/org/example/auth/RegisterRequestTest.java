package org.example.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.example.auth.model.RegisterRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RegisterRequestTest {

    @Test
    @DisplayName("the email is normalized, the password is kept exactly as given")
    void normalization() {
        RegisterRequest request = new RegisterRequest(" Qwe@GMail.com ", " Abcdef1! ");

        assertThat(request.email()).isEqualTo("qwe@gmail.com");
        assertThat(request.password()).isEqualTo(" Abcdef1! ");
    }

    @Test
    @DisplayName("toString() does not reveal the password")
    void toStringHidesThePassword() {
        RegisterRequest request = new RegisterRequest("qwe@gmail.com", "Sup3r-Secret!");

        assertThat(request.toString()).doesNotContain("Sup3r-Secret!").contains("qwe@gmail.com");
    }
}
