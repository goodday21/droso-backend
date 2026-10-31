package org.example.auth;

import java.util.Locale;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Checks the header {@code Origin} of the requests that act on the refresh cookie.
 *
 * <p>The cookie is sent by the browser on its own, so these endpoints are open to cross-site request
 * forgery as long as nothing else is checked. {@code SameSite=Strict} keeps the cookie away from
 * requests of other sites; this check is the second line, for the cases that {@code SameSite} does
 * not cover (an old browser, another origin of the same site, e.g. another port of
 * {@code localhost}). Browsers send {@code Origin} with every {@code POST}, and a page cannot forge
 * it, so a request without it, or from an origin not in {@link AuthProperties#allowedOrigins()}, is
 * refused.
 */
@Component
public class OriginPolicy {

    private final Set<String> allowed;

    public OriginPolicy(AuthProperties properties) {
        this.allowed = Set.copyOf(properties.allowedOrigins());
    }

    /**
     * @param origin the header {@code Origin}, or {@code null} if the request has none
     * @throws OriginNotAllowedException unless the origin is allowed
     */
    public void check(@Nullable String origin) {
        if (origin == null || !allowed.contains(origin.toLowerCase(Locale.ROOT))) {
            throw new OriginNotAllowedException();
        }
    }
}
