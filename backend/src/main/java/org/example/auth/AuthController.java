package org.example.auth;

import jakarta.validation.Valid;
import org.example.auth.model.RegisterRequest;
import org.example.user.model.UserResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registration (and, later, the login) of the users.
 *
 * <p>Invalid requests are answered by {@code ApiExceptionHandler} with {@code 400 VALIDATION_ERROR}
 * or {@code 400 MALFORMED_REQUEST}, a taken email with {@code 409 EMAIL_ALREADY_REGISTERED}, a
 * registration while it is turned off with {@code 403 REGISTRATION_DISABLED}.
 */
@RestController
@RequestMapping(AuthController.PATH)
public class AuthController {

    static final String PATH = "/api/v1/auth";

    private final RegistrationService registrationService;

    public AuthController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    /**
     * Registers a user with the role {@code USER} and answers {@code 201 Created} with the user.
     *
     * <p>There is no {@code Location}: the API has no resource of a single user yet.
     *
     * <p>The body is validated before the registration is checked to be enabled, so a turned-off
     * registration still answers an invalid body with {@code 400}; a valid one gets {@code 403}
     * before the password is hashed.
     */
    @PostMapping(path = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return registrationService.register(request);
    }
}
