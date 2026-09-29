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
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, BearerTokenFilter bearerTokenFilter, AdminAccounts admins) throws Exception {
        return http.csrf(csrf -> csrf.disable()).sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // health/** covers the group endpoints (/actuator/health/container); details
                        // stay hidden by default, so this exposes status only.
                        .requestMatchers("/actuator/health/**").permitAll()
                        // Signing in is how a caller gets a token, so none of it can require one:
                        // the native Apple exchange, the code exchanges, Apple's form_post relay and
                        // the list of providers the login screen offers.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/apple", "/api/v1/auth/apple/callback", "/api/v1/auth/*/code").permitAll()
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
}
