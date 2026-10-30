package org.example.auth;

import jakarta.validation.Valid;
import org.example.auth.model.LoginRequest;
import org.example.auth.model.RegisterRequest;
import org.example.auth.model.TokenResponse;
import org.example.security.AuthenticatedUser;
import org.example.user.model.CurrentUserResponse;
import org.example.user.model.UserResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registration and login of the users.
 *
 * <p>Invalid requests are answered by {@code ApiExceptionHandler} with {@code 400 VALIDATION_ERROR}
 * or {@code 400 MALFORMED_REQUEST}, a taken email with {@code 409 EMAIL_ALREADY_REGISTERED}, a
 * registration while it is turned off with {@code 403 REGISTRATION_DISABLED}, a failed login with
 * {@code 401 INVALID_CREDENTIALS}. The registration and the login are open to anyone; {@code /me}
 * needs an access token ({@code SecurityConfig}).
 */
@RestController
@RequestMapping(AuthController.PATH)
public class AuthController {

    static final String PATH = "/api/v1/auth";

    private final RegistrationService registrationService;
    private final LoginService loginService;

    public AuthController(RegistrationService registrationService, LoginService loginService) {
        this.registrationService = registrationService;
        this.loginService = loginService;
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

    /**
     * Answers {@code 200 OK} with an access token, {@code 401 INVALID_CREDENTIALS} if the email has
     * no active user or the password does not match (the client is not told which).
     *
     * <p>{@code Cache-Control: no-store} (RFC 6749, section 5.1): no cache may keep the token.
     */
    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(loginService.login(request));
    }

    /**
     * The user of the access token, as currently stored (the role may differ from the claim of the
     * token), with the permissions of the role. Open to every authenticated user.
     */
    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return new CurrentUserResponse(user.id(), user.email(), user.role(), user.role().permissions(),
                user.createdAt());
    }
}
