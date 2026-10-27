package org.example.auth;

/**
 * Thrown when someone tries to register while the registration is turned off
 * ({@code droso.auth.registration-enabled=false}). Answered with {@code 403 REGISTRATION_DISABLED}.
 */
public class RegistrationDisabledException extends RuntimeException {

    public RegistrationDisabledException() {
        super("The registration is disabled");
    }
}
