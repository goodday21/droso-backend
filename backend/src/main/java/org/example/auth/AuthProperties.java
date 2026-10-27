package org.example.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the registration and, later, of the login.
 *
 * @param registrationEnabled whether anyone may register with {@code POST /api/v1/auth/register};
 *                            {@code true} by default. A deployment that wants no self-registered
 *                            users turns it off ({@code DROSO_AUTH_REGISTRATION_ENABLED=false})
 */
@ConfigurationProperties(prefix = "droso.auth")
public record AuthProperties(@DefaultValue("true") boolean registrationEnabled) {
}
