package de.joinside.evmap_service.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import java.util.Set;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
class SecurityConfiguration {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    /**
     * CSRF only matters where the browser attaches the credential by itself, i.e. the session cookie.
     * A request without that cookie has no ambient credential to abuse, one with an Authorization
     * header (iOS) never uses it, and also exempt are the sign-in exchanges: they
     * carry no cookie yet, Apple's {@code form_post} relay is cross-site by design, and each is bound
     * to its own flow by {@code state}/PKCE.
     */
    private static RequestMatcher cookieAuthenticatedWrite() {
        RequestMatcher signIn = new OrRequestMatcher(
                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/apple"),
                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/apple/callback"),
                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/*/code"));
        return request -> !SAFE_METHODS.contains(request.getMethod()) && request.getHeader("Authorization") == null
                && hasSessionCookie(request) && !signIn.matches(request);
    }

    private static boolean hasSessionCookie(jakarta.servlet.http.HttpServletRequest request) {
        jakarta.servlet.http.Cookie[] cookies = request.getCookies();
        if (cookies == null) return false;
        for (jakarta.servlet.http.Cookie cookie : cookies) if (SessionCookie.NAME.equals(cookie.getName())) return true;
        return false;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, BearerTokenFilter bearerTokenFilter, AdminAccounts admins, SessionCookie sessionCookie) throws Exception {
        // The token cookie is readable by the page on purpose: Angular echoes it as X-XSRF-TOKEN. The
        // plain (non-XOR) handler is what that echo needs. Being readable is harmless, it protects nothing by itself.
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookieCustomizer(cookie -> cookie.secure(sessionCookie.secure()).sameSite("Lax"));
        return http.csrf(csrf -> csrf.csrfTokenRepository(csrfTokens).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .requireCsrfProtectionMatcher(cookieAuthenticatedWrite()))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // health/** covers the group endpoints (/actuator/health/container); details
                        // stay hidden by default, so this exposes status only.
                        .requestMatchers("/actuator/health/**").permitAll()
                        // Signing in is how a caller gets a token, so none of it can require one:
                        // the native Apple exchange, the code exchanges, Apple's form_post relay and
                        // the list of providers the login screen offers.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/apple", "/api/v1/auth/apple/callback", "/api/v1/auth/*/code", "/api/v1/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/providers").permitAll()
                        // The admin flag is looked up per request (AdminAccounts), not carried in the
                        // token. Anonymous callers still get 401 from the entry point below, signed-in
                        // non-admins 403. The web client's route guard only hides UI; this is the boundary.
                        .requestMatchers("/api/v1/admin/**").access((authentication, context) -> new AuthorizationDecision(
                                authentication.get() != null && authentication.get().getPrincipal() instanceof CurrentUser user
                                        && admins.isAdmin(user.accountId())))
                        // Master data is public to read, and only to read: writing a comment lives
                        // under /stations/** too and must never pass without a bearer token.
                        // The operator directory is master data like the stations themselves: the
                        // filter UI it feeds has to work before anybody signs in.
                        .requestMatchers(HttpMethod.GET, "/api/v1/stations/**", "/api/v1/operators").permitAll()
                        .anyRequest().authenticated())
                // No form login or HTTP basic is configured, so Spring would otherwise answer a
                // missing token with 403; the client treats 401 as "sign in again".
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(bearerTokenFilter, UsernamePasswordAuthenticationFilter.class).build();
    }

    /** Deferred CSRF tokens are only written when something reads them; this makes every response carry the cookie. */
    private static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, FilterChain chain)
                throws jakarta.servlet.ServletException, java.io.IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) token.getToken();
            chain.doFilter(request, response);
        }
    }
}
