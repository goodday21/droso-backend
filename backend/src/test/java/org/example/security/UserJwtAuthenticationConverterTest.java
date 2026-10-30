package org.example.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.example.user.Permission;
import org.example.user.Role;
import org.example.user.User;
import org.example.user.UserRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The authorities of a request, without Spring: the token check itself (signature, user, version)
 * is covered by {@code JwtAuthenticationIntegrationTest}.
 */
class UserJwtAuthenticationConverterTest {

    private final UserRepository repository = mock(UserRepository.class);
    private final UserJwtAuthenticationConverter converter = new UserJwtAuthenticationConverter(repository);

    @ParameterizedTest
    @EnumSource(Role.class)
    void authoritiesAreThePermissionsOfTheStoredRole(Role role) {
        User user = new User("a@b.cd", "{bcrypt}hash", role);
        ReflectionTestUtils.setField(user, "id", 7L);
        when(repository.findById(7L)).thenReturn(Optional.of(user));
        // The claim says ADMIN: it must not matter, only the stored role does.
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256")
                .subject("7").claim(AccessTokenService.VERSION, 0).claim("role", "ADMIN")
                .issuedAt(Instant.now()).build();

        assertThat(converter.convert(jwt).getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyElementsOf(role.permissions().stream().map(Permission::name).toList())
                .noneMatch(authority -> authority.startsWith("ROLE_"));
    }
}
