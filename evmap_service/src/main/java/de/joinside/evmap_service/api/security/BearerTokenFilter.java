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

    BearerTokenFilter(AccessTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                CurrentUser user = tokens.verify(authorization.substring(7));
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, java.util.List.of()));
                // Makes every subsequent log line of this request attributable to the caller.
                LogContext.put(LogContext.USER_ID, user.accountId());
                log.debug("Authenticated request for account {}", user.accountId());
            } catch (IllegalArgumentException rejected) {
                // Never log the token itself — an expired or forged token is a normal, expected event.
                log.warn("Rejected bearer token on {} {}: {}", request.getMethod(), request.getRequestURI(), rejected.getMessage());
            }
        }
        chain.doFilter(request, response);
    }
}
