package org.example.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The administrator created on start-up when there is none ({@link BootstrapAdminService}).
 *
 * <p>Set by the environment variables {@code DROSO_BOOTSTRAP_ADMIN_EMAIL} and
 * {@code DROSO_BOOTSTRAP_ADMIN_PASSWORD}; neither has a default. Both or neither must be set. Once an
 * administrator exists they are ignored and may be removed from the environment.
 *
 * @param email    the login of the administrator; the rules of a registration apply
 * @param password the initial password; the password policy of a registration applies. Never logged
 */
@ConfigurationProperties(prefix = "droso.bootstrap.admin")
public record BootstrapAdminProperties(String email, String password) {

    /** Whether at least one of the two is set (a blank value counts as not set). */
    boolean anySet() {
        return isSet(email) || isSet(password);
    }

    /** Whether both are set. */
    boolean bothSet() {
        return isSet(email) && isSet(password);
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Hides both: the generated {@code toString()} of a record would print the password into any
     * log, and the email is personal data.
     */
    @Override
    public String toString() {
        return "BootstrapAdminProperties[email=" + (isSet(email) ? "***" : "<not set>")
                + ", password=" + (isSet(password) ? "***" : "<not set>") + "]";
    }
}
