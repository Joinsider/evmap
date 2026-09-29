package de.joinside.evmap_service.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import de.joinside.evmap_service.logging.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
class BearerTokenFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(BearerTokenFilter.class);

    private final AccessTokenService tokens;
    private final SessionCookie sessionCookie;

    BearerTokenFilter(AccessTokenService tokens, SessionCookie sessionCookie) {
        this.tokens = tokens;
        this.sessionCookie = sessionCookie;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        // An Authorization header wins outright: a request that carries one is never treated as
        // cookie-authenticated, which is also what exempts it from CSRF protection.
        String token = tokenOf(request);
        if (token != null) {
            try {
                CurrentUser user = tokens.verify(token);
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, java.util.List.of()));
                // Makes every subsequent log line of this request attributable to the caller.
                LogContext.put(LogContext.USER_ID, user.accountId());
                log.debug("Authenticated request for account {}", user.accountId());
            } catch (IllegalArgumentException rejected) {
                // Never log the token itself — an expired or forged token is a normal, expected event.
                log.warn("Rejected access token on {} {}: {}", request.getMethod(), request.getRequestURI(), rejected.getMessage());
            }
        }
        chain.doFilter(request, response);
    }

    private String tokenOf(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null) return sessionCookie.read(request).orElse(null);
        return authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
    }
}
