package org.example.security;

import java.time.Duration;
import java.util.List;

import org.example.auth.AuthProperties;
import org.example.user.UserRepository;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Which requests need an access token, and which web pages may call the API from a browser.
 *
 * <p>Every request needs an access token, except the few listed here. There is no session: the
 * access token travels in the header {@code Authorization: Bearer ...} only. The one cookie is the
 * refresh token, sent by the browser to {@code /api/v1/auth} only and accepted by the refresh and the
 * logout, which check the header {@code Origin} themselves ({@code OriginPolicy}). CSRF protection of
 * Spring is therefore off: a browser never adds the bearer header on its own, and the cookie is
 * guarded by {@code SameSite=Strict} and that check. Form login and HTTP Basic are off as well; a
 * password is accepted by {@code POST /api/v1/auth/login} only.
 *
 * <p>Cross-origin requests (CORS) are allowed from {@link AuthProperties#allowedOrigins()} only,
 * with credentials (the refresh cookie). A request from any other origin is refused before
 * anything else.
 *
 * <p>Failures are answered as {@code ApiError}: {@code 401 UNAUTHORIZED} without a valid token
 * ({@link ApiAuthenticationEntryPoint}), {@code 403 FORBIDDEN} with a token that is not enough
 * ({@link ApiAccessDeniedHandler}) or from an origin that is not allowed ({@link ApiCorsProcessor}).
 * A URL rejected by the firewall of Spring Security is a {@code 400 BAD_REQUEST}
 * ({@link ApiRequestRejectedHandler}).
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
            UserRepository users,
            AuthProperties authProperties,
            ApiErrorWriter errorWriter,
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
                // Placed by Spring Security before the authentication: a preflight is answered here,
                // without a token, and a foreign origin is refused before its token is looked at.
                .addFilter(corsFilter(authProperties, errorWriter))
                .authorizeHttpRequests(requests -> requests
                        // The public health contract (followed by droso-frontend) works without a login.
                        .requestMatchers("/api/v1/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        // They are called when the access token has expired: the refresh cookie
                        // authenticates them, and they check the origin themselves.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
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

    /**
     * CORS for the API: the allowed origins with credentials, so that a page served from another
     * origin (the development server of droso-frontend without its proxy, a separate domain of the
     * frontend) can log in, refresh and call the API.
     *
     * <p>Not a bean: a {@code CorsFilter} bean would also be registered as a servlet filter of its
     * own and run twice, and a {@code CorsConfigurationSource} bean would make Spring Security build
     * a second filter without {@link ApiCorsProcessor}.
     */
    static CorsFilter corsFilter(AuthProperties properties, ApiErrorWriter errorWriter) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        cors.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));
        // Location of a created defect; WWW-Authenticate of a 401.
        cors.setExposedHeaders(List.of(HttpHeaders.LOCATION, HttpHeaders.WWW_AUTHENTICATE));
        cors.setAllowCredentials(true);
        cors.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        CorsFilter filter = new CorsFilter(source);
        filter.setCorsProcessor(new ApiCorsProcessor(errorWriter));
        return filter;
    }
}
