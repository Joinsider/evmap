package de.joinside.evmap_service.api.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * The web client's sign-in, carried as an {@code HttpOnly} cookie holding the same signed token the
 * iOS app sends as a bearer header (ADR 0018). Page scripts cannot read it, so an XSS cannot lift the
 * session; the price is that the browser attaches it on its own, which is what the CSRF protection in
 * {@link SecurityConfiguration} is for. {@code Path=/api} keeps it away from the static pages, and
 * {@code SameSite=Lax} is the first line of that defence.
 */
@Component
public class SessionCookie {
    public static final String NAME = "evmap_session";

    private final Duration ttl;
    private final boolean secure;

    SessionCookie(@Value("${evmap.security.jwt-ttl}") Duration ttl, @Value("${evmap.security.session-cookie-secure:true}") boolean secure) {
        this.ttl = ttl;
        this.secure = secure;
    }

    public ResponseCookie issue(String token) {
        return builder(token).maxAge(ttl).build();
    }

    public ResponseCookie clear() {
        return builder("").maxAge(Duration.ZERO).build();
    }

    boolean secure() {
        return secure;
    }

    Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName()) && !cookie.getValue().isEmpty()) return Optional.of(cookie.getValue());
        }
        return Optional.empty();
    }

    private ResponseCookie.ResponseCookieBuilder builder(String value) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(secure).sameSite("Lax").path("/api");
    }
}
