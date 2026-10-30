package org.example.user;

/**
 * The change would leave no active administrator: nobody could manage the users any more, and the
 * bootstrap of a new one only runs on the next start-up. Answered with {@code 409 LAST_ADMIN}.
 */
public class LastAdminException extends RuntimeException {

    public LastAdminException() {
        super("The last active administrator cannot be demoted or deleted");
    }
}
