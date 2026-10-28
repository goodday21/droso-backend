package org.example.auth.model;

import org.example.security.AccessTokenService.AccessToken;

/**
 * Response body of a successful login.
 *
 * <pre>{@code
 * { "accessToken": "eyJ...", "tokenType": "Bearer", "expiresIn": 1800 }
 * }</pre>
 *
 * @param accessToken the JWT, to be sent as {@code Authorization: Bearer <accessToken>}
 * @param tokenType   always {@code Bearer} (RFC 6750)
 * @param expiresIn   the lifetime of the token in seconds, as in an OAuth 2.0 token response
 */
public record TokenResponse(String accessToken, String tokenType, long expiresIn) {

    public static final String BEARER = "Bearer";

    public static TokenResponse of(AccessToken token) {
        return new TokenResponse(token.value(), BEARER, token.expiresIn().toSeconds());
    }

    /** Hides the token: whoever reads it in a log could act as the user until it expires. */
    @Override
    public String toString() {
        return "TokenResponse[accessToken=***, tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
    }
}
