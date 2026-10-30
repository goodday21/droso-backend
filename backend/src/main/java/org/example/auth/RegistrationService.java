package org.example.auth;

import org.example.auth.model.RegisterRequest;
import org.example.user.Role;
import org.example.user.User;
import org.example.user.UserRepository;
import org.example.user.model.UserResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The registration of a new user by themselves.
 */
@Service
public class RegistrationService {

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;

    public RegistrationService(UserRepository repository, PasswordEncoder passwordEncoder,
            AuthProperties properties) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    /**
     * Creates an active user with the role {@link Role#USER}. The request is expected to be
     * validated already; {@link User} still checks the email on its own.
     *
     * <p>Whether the email is taken is decided by the unique index alone, not by a query before
     * the insert: of two concurrent registrations of one address, a query would let both pass.
     *
     * @throws RegistrationDisabledException   if the registration is turned off
     * @throws EmailAlreadyRegisteredException if an active user already has this email
     */
    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (!properties.registrationEnabled()) {
            throw new RegistrationDisabledException();
        }
        User user = new User(request.email(), passwordEncoder.encode(request.password()), Role.USER);
        try {
            // saveAndFlush: the INSERT runs here, so a duplicate surfaces inside this method.
            return UserResponse.of(repository.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            if (UserRepository.isEmailTaken(e)) {
                throw new EmailAlreadyRegisteredException(e);
            }
            throw e;
        }
    }
}
