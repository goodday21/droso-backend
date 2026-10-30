package org.example.bootstrap;

/**
 * The configured administrator cannot be created; thrown on start-up, it stops the application.
 * The message says what to fix and never contains the email or the password.
 */
public class BootstrapAdminException extends RuntimeException {

    BootstrapAdminException(String message) {
        super(message);
    }
}
