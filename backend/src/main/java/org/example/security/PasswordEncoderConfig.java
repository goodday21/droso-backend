package org.example.security;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The hashing of the passwords.
 *
 * <p>A password is hashed, never encrypted: a hash cannot be turned back into the password, even
 * by someone who has the database and the configuration. BCrypt is salted and deliberately slow;
 * the cost 12 takes about a quarter of a second per hash, which makes guessing the passwords of a
 * stolen table expensive while a login stays fast enough.
 *
 * <p>The hash is stored with the id of its algorithm, e.g. {@code {bcrypt}$2a$12$...}. Another
 * algorithm (Argon2id) or a higher cost can therefore be introduced later without a reset: the
 * stored hashes are still checked with the algorithm they name.
 *
 * <p>{@code PasswordEncoderFactories.createDelegatingPasswordEncoder()} is not used: its BCrypt has
 * the cost 10, and it accepts hashes of legacy algorithms ({@code {noop}}, {@code {sha256}}) that
 * have no place here.
 */
@Configuration(proxyBeanMethods = false)
public class PasswordEncoderConfig {

    static final String BCRYPT = "bcrypt";
    static final int BCRYPT_COST = 12;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder(BCRYPT, Map.of(BCRYPT, new BCryptPasswordEncoder(BCRYPT_COST)));
    }
}
