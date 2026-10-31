package org.example.auth;

import jakarta.validation.Valid;
import org.example.auth.RefreshTokenService.SessionTokens;
import org.example.auth.model.LoginRequest;
import org.example.auth.model.RegisterRequest;
import org.example.auth.model.TokenResponse;
import org.example.security.AuthenticatedUser;
import org.example.user.model.CurrentUserResponse;
import org.example.user.model.UserResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registration, login, refresh and logout of the users.
 *
 * <p>Invalid requests are answered by {@code ApiExceptionHandler} with {@code 400 VALIDATION_ERROR}
 * or {@code 400 MALFORMED_REQUEST}, a taken email with {@code 409 EMAIL_ALREADY_REGISTERED}, a
 * registration while it is turned off with {@code 403 REGISTRATION_DISABLED}, a failed login with
 * {@code 401 INVALID_CREDENTIALS}, a failed refresh with {@code 401 INVALID_REFRESH_TOKEN}, a
 * refresh or logout from an origin that is not allowed with {@code 403 FORBIDDEN}. The registration,
 * the login, the refresh and the logout are open to anyone; {@code /me} and {@code /logout-all} need
 * an access token ({@code SecurityConfig}).
 *
 * <p>The refresh token travels in the cookie {@link RefreshCookie} only, never in a body: a script of
 * the page cannot read it.
 */
@RestController
@RequestMapping(AuthController.PATH)
public class AuthController {

    static final String PATH = "/api/v1/auth";

    private final RegistrationService registrationService;
    private final LoginService loginService;
    private final RefreshTokenService refreshTokens;
    private final OriginPolicy originPolicy;

    public AuthController(RegistrationService registrationService, LoginService loginService,
            RefreshTokenService refreshTokens, OriginPolicy originPolicy) {
        this.registrationService = registrationService;
        this.loginService = loginService;
        this.refreshTokens = refreshTokens;
        this.originPolicy = originPolicy;
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
     * Answers {@code 200 OK} with an access token and sets the refresh cookie;
     * {@code 401 INVALID_CREDENTIALS} if the email has no active user or the password does not match
     * (the client is not told which).
     *
     * <p>{@code Cache-Control: no-store} (RFC 6749, section 5.1): no cache may keep the token.
     */
    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return issued(loginService.login(request));
    }

    /**
     * Exchanges the refresh cookie for a new access token and a new cookie (the old one is revoked).
     * No body, no access token: it is called when the access token has expired.
     *
     * <p>The origin is checked before the cookie is looked at, so that a forged request changes
     * nothing.
     */
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@RequestHeader(name = HttpHeaders.ORIGIN, required = false) @Nullable String origin,
            @CookieValue(name = RefreshCookie.NAME, required = false) @Nullable String refreshToken) {
        originPolicy.check(origin);
        return issued(refreshTokens.refresh(refreshToken).orElseThrow(InvalidRefreshTokenException::new));
    }

    /**
     * Ends the login of this browser: revokes the refresh tokens of the cookie and deletes the
     * cookie. Always {@code 204}, with or without a valid cookie. The access token stays valid until
     * it expires; the client forgets it.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(name = HttpHeaders.ORIGIN, required = false) @Nullable String origin,
            @CookieValue(name = RefreshCookie.NAME, required = false) @Nullable String refreshToken) {
        originPolicy.check(origin);
        refreshTokens.logout(refreshToken);
        return loggedOut();
    }

    /**
     * Ends every login of the user, on every device: every access token (this one included) and
     * every refresh token is revoked at once. Needs an access token, so no check of the origin: a
     * forged cross-site request carries none.
     */
    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal AuthenticatedUser user) {
        refreshTokens.logoutEverywhere(user.id());
        return loggedOut();
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

    private static ResponseEntity<TokenResponse> issued(SessionTokens tokens) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, RefreshCookie.of(tokens.refreshToken()))
                .body(TokenResponse.of(tokens.accessToken()));
    }

    private static ResponseEntity<Void> loggedOut() {
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, RefreshCookie.cleared()).build();
    }
}
