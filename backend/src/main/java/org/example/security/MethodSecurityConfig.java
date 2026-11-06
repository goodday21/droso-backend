package org.example.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Turns on {@code @PreAuthorize}, with which the controllers require a
 * {@linkplain org.example.user.Permission permission} ({@code hasAuthority('TICKET_READ')}), never a
 * role. A denied call throws an {@code AccessDeniedException} inside Spring MVC, which
 * {@code ApiExceptionHandler} answers with {@code 403 FORBIDDEN}.
 *
 * <p>Apart from {@link SecurityConfig}, which holds the rules of the filter chain (who needs a token
 * at all); here only the checks of the methods.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class MethodSecurityConfig {
}
