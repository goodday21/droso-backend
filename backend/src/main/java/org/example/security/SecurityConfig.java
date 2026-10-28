package org.example.security;

import org.example.user.UserRepository;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Which requests need an access token.
 *
 * <p>Every request needs one, except the few listed here. The API is stateless: there is no
 * session and no cookie, the token travels in the header {@code Authorization: Bearer ...} only.
 * CSRF protection is therefore off: a browser never adds that header on its own, so a forged
 * cross-site request carries no token. Form login and HTTP Basic are off as well; a password is
 * accepted by {@code POST /api/v1/auth/login} only.
 *
 * <p>Failures are answered as {@code ApiError}: {@code 401 UNAUTHORIZED} without a valid token
 * ({@link ApiAuthenticationEntryPoint}), {@code 403 FORBIDDEN} with a token that is not enough
 * ({@link ApiAccessDeniedHandler}). A URL rejected by the firewall of Spring Security is a
 * {@code 400 BAD_REQUEST} ({@link ApiRequestRejectedHandler}).
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
            UserRepository users,
            ApiAuthenticationEntryPoint entryPoint,
            ApiAccessDeniedHandler accessDeniedHandler,
            ApiAuthenticationFailureHandler failureHandler) {

        UserJwtAuthenticationConverter converter = new UserJwtAuthenticationConverter(users);
        http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests
                        // The public health contract (followed by droso-frontend) works without a login.
                        .requestMatchers("/api/v1/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        // The error page renders the errors raised outside Spring MVC; without this
                        // every such error of an anonymous request would turn into a 401.
                        .requestMatchers("/error").permitAll()
                        // The probe of the platform needs no token and shows no details
                        // (show-details: never).
                        .requestMatchers(EndpointRequest.to("health")).permitAll()
                        // The other endpoints of Actuator (metrics) stay closed until the deployment
                        // decides who reads them: a management port or a permission (TASK-008).
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).denyAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(server -> server
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        // The DSL has no setter for it: a database failure while checking a token
                        // must answer 500, not escape the filter (ApiAuthenticationFailureHandler).
                        .withObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                            @Override
                            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                                filter.setAuthenticationFailureHandler(failureHandler);
                                return filter;
                            }
                        }))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }
}
