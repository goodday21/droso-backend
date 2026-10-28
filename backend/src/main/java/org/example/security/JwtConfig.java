package org.example.security;

import java.time.Clock;
import java.util.List;

import javax.crypto.SecretKey;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Signing and verification of the access tokens with one shared secret (HS256, Nimbus).
 *
 * <p>The server both issues and verifies the tokens, so a symmetric key is enough; Spring Boot has
 * no property for it, hence the beans here. The decoder accepts HS256 only: a token that names
 * another algorithm, {@code none} included, is rejected before its signature is even looked at, so
 * that an attacker cannot choose how the token is checked.
 */
@Configuration(proxyBeanMethods = false)
public class JwtConfig {

    @Bean
    public JwtEncoder jwtEncoder(JwtProperties properties) {
        return NimbusJwtEncoder.withSecretKey(properties.secretKey()).algorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    public JwtDecoder jwtDecoder(JwtProperties properties, Clock clock) {
        SecretKey key = properties.secretKey();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(validator(properties, clock));
        return decoder;
    }

    /**
     * The checks of the claims after the signature: issued by this server, not expired (the default
     * leeway of 60 seconds), and carrying a subject and a version. Without {@code exp} a token would
     * never expire, so it is required as well.
     */
    static OAuth2TokenValidator<Jwt> validator(JwtProperties properties, Clock clock) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        timestamps.setClock(clock);
        timestamps.setAllowEmptyExpiryClaim(false);
        return new DelegatingOAuth2TokenValidator<>(List.of(
                timestamps,
                new JwtIssuerValidator(properties.issuer()),
                new JwtClaimValidator<>(AccessTokenService.SUBJECT, subject -> subject != null),
                new JwtClaimValidator<>(AccessTokenService.VERSION, version -> version != null)));
    }
}
