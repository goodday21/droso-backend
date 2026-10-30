package org.example.bootstrap;

import java.util.List;

import org.example.user.EmailAddress;
import org.example.user.PasswordPolicy;
import org.example.user.Role;
import org.example.user.User;
import org.example.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Creates the first administrator, so that a new installation can be managed at all: the
 * registration only ever creates users with the role {@code USER}.
 *
 * <p>The administrator is <em>created</em>, never granted to an existing user: anyone may register
 * the configured email before the start-up (or nobody may, with the registration turned off), so
 * granting the role by email would hand it to whoever came first. If the email is taken, the
 * start-up fails instead, and the operator chooses another address.
 *
 * <p>No transaction around the whole: each call of the repository has its own, so that after a
 * failed insert the check for an administrator can still run (see {@link #bootstrap}).
 */
@Service
public class BootstrapAdminService {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminService.class);

    /** What {@link #bootstrap} did. */
    public enum Outcome {
        /** The administrator was created. */
        CREATED,
        /** An active administrator exists already; the settings were ignored. */
        ADMIN_EXISTS,
        /** There is no administrator and none is configured. */
        NOT_CONFIGURED
    }

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;

    public BootstrapAdminService(UserRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Creates the configured administrator unless an active one exists.
     *
     * <p>Two instances starting at once against an empty database may both find no administrator;
     * the unique index lets one of them insert, and the other, failing on the taken email, finds the
     * administrator of the first and carries on.
     *
     * @throws BootstrapAdminException if no administrator exists and the settings are incomplete or
     *                                 invalid, or the email belongs to an active user
     */
    public Outcome bootstrap(BootstrapAdminProperties properties) {
        if (adminExists()) {
            if (properties.anySet()) {
                log.info("An administrator exists already: DROSO_BOOTSTRAP_ADMIN_* are ignored and may be removed");
            }
            return Outcome.ADMIN_EXISTS;
        }
        if (!properties.anySet()) {
            log.warn("There is no administrator, so nobody can manage the users: set DROSO_BOOTSTRAP_ADMIN_EMAIL "
                    + "and DROSO_BOOTSTRAP_ADMIN_PASSWORD to create one on the next start-up");
            return Outcome.NOT_CONFIGURED;
        }
        User admin = newAdmin(properties);
        try {
            repository.saveAndFlush(admin);
        } catch (DataIntegrityViolationException e) {
            if (!UserRepository.isEmailTaken(e)) {
                throw e;
            }
            if (adminExists()) {
                log.info("An administrator was created concurrently by another instance");
                return Outcome.ADMIN_EXISTS;
            }
            throw new BootstrapAdminException("DROSO_BOOTSTRAP_ADMIN_EMAIL is the email of an existing user, who "
                    + "is not made an administrator: choose an address that is not registered");
        }
        log.info("Created the administrator with the id {}", admin.getId());
        return Outcome.CREATED;
    }

    private boolean adminExists() {
        return repository.existsByRoleAndDeletedAtIsNull(Role.ADMIN);
    }

    /** Checks the settings by the rules of a registration; hashes the password only once they pass. */
    private User newAdmin(BootstrapAdminProperties properties) {
        if (!properties.bothSet()) {
            throw new BootstrapAdminException("DROSO_BOOTSTRAP_ADMIN_EMAIL and DROSO_BOOTSTRAP_ADMIN_PASSWORD "
                    + "must be set together");
        }
        if (!EmailAddress.isValid(EmailAddress.normalize(properties.email()))) {
            throw new BootstrapAdminException("DROSO_BOOTSTRAP_ADMIN_EMAIL is not a valid email address");
        }
        // The messages of the policy name the rules, never the password.
        List<String> violations = PasswordPolicy.violations(properties.password());
        if (!violations.isEmpty()) {
            throw new BootstrapAdminException("DROSO_BOOTSTRAP_ADMIN_PASSWORD violates the password policy: "
                    + String.join("; ", violations));
        }
        return new User(properties.email(), passwordEncoder.encode(properties.password()), Role.ADMIN);
    }
}
