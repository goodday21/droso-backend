package org.example.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.example.support.PostgresIntegrationTest;
import org.example.user.Permission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every endpoint of Spring MVC needs an access token, except the ones listed in {@link #PUBLIC}.
 *
 * <p>The endpoints are not listed by hand: they are read from the {@link RequestMappingHandlerMapping}
 * of the running application, so that a new endpoint is checked as soon as it exists. A matcher of
 * {@code SecurityConfig} that opens more than it should (e.g. {@code /api/v1/auth/**}, which would
 * open {@code /me} too) makes this test fail, and so does a new public endpoint that is not added to
 * {@link #PUBLIC} on purpose.
 *
 * <p>Beyond a token, every protected endpoint must require a {@link Permission} with
 * {@code @PreAuthorize("hasAuthority('...')")}, except those in {@link #ANY_USER}: an endpoint that
 * forgets it would be open to every role.
 *
 * <p>The endpoints of Actuator are mapped elsewhere; their access is checked by
 * {@link JwtAuthenticationIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EndpointAccessIntegrationTest extends PostgresIntegrationTest {

    /**
     * The requests that need no token, as "METHOD path" with the patterns of the mappings. A mapping
     * without a method (the error page) is requested with GET.
     */
    private static final Set<String> PUBLIC = Set.of(
            "GET /api/v1/health",
            "POST /api/v1/auth/register",
            "POST /api/v1/auth/login",
            // Authenticated by the refresh cookie, called when the access token has expired.
            "POST /api/v1/auth/refresh",
            "POST /api/v1/auth/logout",
            "GET /error");

    /** The protected endpoints that any authenticated user may call, whatever the role. */
    private static final Set<String> ANY_USER = Set.of("GET /api/v1/auth/me", "POST /api/v1/auth/logout-all");

    /** {@code hasAuthority('<PERMISSION>')}, the only form of check the API uses. */
    private static final Pattern HAS_AUTHORITY = Pattern.compile("hasAuthority\\('([A-Z_]+)'\\)");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    /** One "METHOD path" per method and pattern of every mapping of the application. */
    private SortedSet<String> endpoints() {
        SortedSet<String> endpoints = new TreeSet<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                if (methods.isEmpty()) {
                    endpoints.add("GET " + pattern);
                }
                methods.forEach(method -> endpoints.add(method.name() + " " + pattern));
            }
        }
        return endpoints;
    }

    /**
     * Sends the request of an endpoint without a token. A path variable is {@code 1}; a body, if the
     * method takes one, is an empty JSON object, so that a public endpoint answers with its own
     * validation rather than with 415.
     */
    private ResultActions send(String endpoint) throws Exception {
        String[] parts = endpoint.split(" ", 2);
        HttpMethod method = HttpMethod.valueOf(parts[0]);
        String path = parts[1].replaceAll("\\{[^}]+}", "1");
        var builder = request(method, path);
        if (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH) {
            builder.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        return mockMvc.perform(builder);
    }

    @TestFactory
    @DisplayName("every endpoint outside the public list answers 401 UNAUTHORIZED without a token")
    Stream<DynamicTest> protectedEndpoints() {
        return endpoints().stream()
                .filter(endpoint -> !PUBLIC.contains(endpoint))
                .map(endpoint -> DynamicTest.dynamicTest(endpoint, () -> send(endpoint)
                        .andExpect(status().isUnauthorized())
                        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))));
    }

    @TestFactory
    @DisplayName("every endpoint of the public list is reached without a token")
    Stream<DynamicTest> publicEndpoints() {
        return PUBLIC.stream().sorted()
                .map(endpoint -> DynamicTest.dynamicTest(endpoint, () -> assertThat(
                        send(endpoint).andReturn().getResponse().getStatus()).isNotEqualTo(401)));
    }

    @TestFactory
    @DisplayName("every protected endpoint outside ANY_USER requires an existing permission")
    Stream<DynamicTest> permissions() {
        Map<String, HandlerMethod> handlers = new TreeMap<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                methods.forEach(method -> handlers.put(method.name() + " " + pattern, handler));
            }
        });
        return handlers.entrySet().stream()
                .filter(entry -> !PUBLIC.contains(entry.getKey()) && !ANY_USER.contains(entry.getKey()))
                .map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
                    PreAuthorize check = entry.getValue().getMethodAnnotation(PreAuthorize.class);
                    assertThat(check).as("@PreAuthorize of %s", entry.getKey()).isNotNull();
                    Matcher matcher = HAS_AUTHORITY.matcher(check.value());
                    assertThat(matcher.matches()).as("a single hasAuthority('...'): %s", check.value()).isTrue();
                    assertThat(Permission.valueOf(matcher.group(1))).isNotNull();
                }));
    }

    @Test
    @DisplayName("the endpoints are found, and the public list names existing ones only")
    void endpointsAreFound() {
        // Without this, an empty scan or a renamed public endpoint would pass unnoticed.
        assertThat(endpoints())
                .containsAll(PUBLIC)
                .containsAll(ANY_USER)
                .contains("GET /api/v1/auth/me", "POST /api/v1/defects", "GET /api/v1/defects/{id}");
    }
}
