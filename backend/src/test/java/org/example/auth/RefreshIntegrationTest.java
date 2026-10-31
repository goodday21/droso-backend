package org.example.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.support.TestUsers.authorization;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.example.support.PostgresIntegrationTest;
import org.example.support.TestUsers;
import org.example.user.Role;
import org.example.user.User;
import org.example.user.UserManagementService;
import org.example.user.UserRepository;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /api/v1/auth/refresh}, {@code /logout} and {@code /logout-all} through the whole
 * application, with the refresh cookie as a browser sends it. One case per kind of answer; the
 * decisions behind them are pinned by {@link RefreshTokenServiceTest}, the origins by
 * {@link OriginPolicyTest}, the cookie by {@link RefreshCookieTest}.
 *
 * <p>The configuration of {@code LoginIntegrationTest}, so that both share one Spring context.
 */
@SpringBootTest(properties = "droso.auth.registration-enabled=true")
@AutoConfigureMockMvc
class RefreshIntegrationTest extends PostgresIntegrationTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String REFRESH = "/api/v1/auth/refresh";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String LOGOUT_ALL = "/api/v1/auth/logout-all";
    private static final String ME = "/api/v1/auth/me";

    /** The default of droso.auth.allowed-origins. */
    private static final String ORIGIN = "http://localhost:4200";
    private static final String FOREIGN_ORIGIN = "https://evil.example.com";

    private static final Pattern COOKIE_VALUE = Pattern.compile("^droso_refresh=([^;]*);");

    private static final String INVALID_REFRESH_TOKEN = "{\"status\":401,\"code\":\"INVALID_REFRESH_TOKEN\","
            + "\"message\":\"Invalid refresh token\",\"fieldErrors\":{}}";
    private static final String FORBIDDEN = "{\"status\":403,\"code\":\"FORBIDDEN\","
            + "\"message\":\"Forbidden\",\"fieldErrors\":{}}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private UserRepository users;

    @Autowired
    private UserManagementService userManagement;

    private User user;

    @BeforeEach
    void storeUser() {
        user = TestUsers.create(users, Role.USER);
    }

    /** The value of the refresh cookie set by a response. */
    private static String cookie(MvcResult result) {
        Matcher matcher = COOKIE_VALUE.matcher(result.getResponse().getHeader(HttpHeaders.SET_COOKIE));
        assertThat(matcher.find()).as("a refresh cookie is set").isTrue();
        return matcher.group(1);
    }

    private static String accessToken(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private MvcResult login() throws Exception {
        return mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + user.getEmail() + "\", \"password\": \"" + TestUsers.PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post(REFRESH).header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(new Cookie(RefreshCookie.NAME, refreshToken)));
    }

    private void assertJson(ResultActions result, String expected) throws Exception {
        assertThat(jsonMapper.readTree(result.andReturn().getResponse().getContentAsString()))
                .isEqualTo(jsonMapper.readTree(expected));
    }

    /** 401 INVALID_REFRESH_TOKEN, the cookie deleted, no bearer challenge. */
    private void assertInvalidRefreshToken(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, Matchers.startsWith("droso_refresh=; Path=/api/v1/auth; Max-Age=0;")));
        assertJson(result, INVALID_REFRESH_TOKEN);
    }

    // ---------------------------------------------------------------- refresh

    @Test
    @DisplayName("a refresh answers a new access token and a new cookie, which no cache may keep, for the allowed origin")
    void refresh() throws Exception {
        String first = cookie(login());

        MvcResult result = refresh(first)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.*", Matchers.hasSize(3)))
                .andReturn();

        assertThat(cookie(result)).isNotEqualTo(first).matches("[A-Za-z0-9_-]{43}");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(cookie(result));
        mockMvc.perform(get(ME).with(authorization("Bearer " + accessToken(result))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId()));
    }

    @Test
    @DisplayName("the replaced cookie presented again answers 401 and ends the login: the new cookie fails too")
    void reuse() throws Exception {
        String first = cookie(login());
        String second = cookie(refresh(first).andExpect(status().isOk()).andReturn());

        assertInvalidRefreshToken(refresh(first));
        assertInvalidRefreshToken(refresh(second));
    }

    @Test
    @DisplayName("a refresh without the cookie answers 401 INVALID_REFRESH_TOKEN")
    void noCookie() throws Exception {
        assertInvalidRefreshToken(mockMvc.perform(post(REFRESH).header(HttpHeaders.ORIGIN, ORIGIN)));
    }

    @Test
    @DisplayName("a cookie of a deleted user answers 401")
    void deletedUser() throws Exception {
        String refreshToken = cookie(login());
        userManagement.delete(user.getId());

        assertInvalidRefreshToken(refresh(refreshToken));
    }

    @Test
    @DisplayName("a refresh without Origin answers 403 FORBIDDEN and leaves the cookie usable")
    void noOrigin() throws Exception {
        String refreshToken = cookie(login());

        ResultActions result = mockMvc.perform(post(REFRESH).cookie(new Cookie(RefreshCookie.NAME, refreshToken)))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        assertJson(result, FORBIDDEN);

        refresh(refreshToken).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a refresh from a foreign origin answers 403 FORBIDDEN before the cookie is looked at")
    void foreignOrigin() throws Exception {
        String refreshToken = cookie(login());

        ResultActions result = mockMvc.perform(post(REFRESH).header(HttpHeaders.ORIGIN, FOREIGN_ORIGIN)
                        .cookie(new Cookie(RefreshCookie.NAME, refreshToken)))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertJson(result, FORBIDDEN);

        refresh(refreshToken).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a refresh with an invalid access token sent along answers 401 UNAUTHORIZED: a client sends none")
    void refreshWithInvalidAccessToken() throws Exception {
        // Pinned on purpose (README): the filter of the bearer tokens rejects an invalid token before
        // the endpoint is reached, even an open one, and the cookie stays untouched.
        String refreshToken = cookie(login());

        mockMvc.perform(post(REFRESH).header(HttpHeaders.ORIGIN, ORIGIN).with(authorization("Bearer expired"))
                        .cookie(new Cookie(RefreshCookie.NAME, refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        refresh(refreshToken).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- CORS

    @Test
    @DisplayName("a preflight from the allowed origin is answered without a token, with credentials allowed")
    void preflight() throws Exception {
        mockMvc.perform(options(REFRESH).header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, Matchers.containsString("POST")));
    }

    @Test
    @DisplayName("a request of any endpoint from a foreign origin answers 403 FORBIDDEN, even with a valid token")
    void foreignOriginElsewhere() throws Exception {
        String bearer = "Bearer " + accessToken(login());

        ResultActions result = mockMvc.perform(get(ME).header(HttpHeaders.ORIGIN, FOREIGN_ORIGIN).with(authorization(bearer)))
                .andExpect(status().isForbidden());
        assertJson(result, FORBIDDEN);
    }

    // ---------------------------------------------------------------- logout

    @Test
    @DisplayName("a logout answers 204, deletes the cookie, and the cookie no longer refreshes")
    void logout() throws Exception {
        String refreshToken = cookie(login());

        mockMvc.perform(post(LOGOUT).header(HttpHeaders.ORIGIN, ORIGIN).cookie(new Cookie(RefreshCookie.NAME, refreshToken)))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, Matchers.startsWith("droso_refresh=; Path=/api/v1/auth; Max-Age=0;")));

        assertInvalidRefreshToken(refresh(refreshToken));
    }

    @Test
    @DisplayName("a logout without a cookie still answers 204")
    void logoutWithoutCookie() throws Exception {
        mockMvc.perform(post(LOGOUT).header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("a logout without Origin answers 403 and the login goes on")
    void logoutWithoutOrigin() throws Exception {
        String refreshToken = cookie(login());

        mockMvc.perform(post(LOGOUT).cookie(new Cookie(RefreshCookie.NAME, refreshToken)))
                .andExpect(status().isForbidden());

        refresh(refreshToken).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a logout everywhere answers 204 and ends every login: access tokens and cookies of every device fail")
    void logoutAll() throws Exception {
        MvcResult laptop = login();
        MvcResult phone = login();
        String bearer = "Bearer " + accessToken(laptop);

        mockMvc.perform(post(LOGOUT_ALL).with(authorization(bearer)))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, Matchers.startsWith("droso_refresh=; Path=/api/v1/auth; Max-Age=0;")));

        mockMvc.perform(get(ME).with(authorization(bearer))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(ME).with(authorization("Bearer " + accessToken(phone)))).andExpect(status().isUnauthorized());
        assertInvalidRefreshToken(refresh(cookie(laptop)));
        assertInvalidRefreshToken(refresh(cookie(phone)));
    }
}
