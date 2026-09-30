package de.joinside.evmap_service.api.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public read paths and the comment write path share the {@code /api/v1/stations/**} prefix, so
 * a permit rule without an HTTP method opens the write too — the handler then meets a {@code null}
 * principal and answers 500. The real controllers' services are package-private to their own
 * packages, so stand-ins on the same routes are what reach the handler here: the filter chain is
 * the thing under test, not the controllers.
 */
@WebMvcTest(controllers = SecurityConfigurationTests.Routes.class)
@Import({SecurityConfiguration.class, AccessTokenService.class, SessionCookie.class, SecurityConfigurationTests.Routes.class, SecurityConfigurationTests.Admins.class})
class SecurityConfigurationTests {

    private static final String COMMENTS = "/api/v1/stations/{stationId}/comments";
    private static final String BODY = "{\"body\":\"Works fine\"}";
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID GONE = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccessTokenService tokens;

    @Test
    @DisplayName("writing a comment without a bearer token is refused with 401")
    void anonymousCommentIsUnauthorized() throws Exception {
        mockMvc.perform(post(COMMENTS, UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("writing a comment with a forged bearer token is refused with 401")
    void forgedTokenIsUnauthorized() throws Exception {
        mockMvc.perform(post(COMMENTS, UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("writing a comment with a valid bearer token reaches the handler")
    void authenticatedCommentIsCreated() throws Exception {
        mockMvc.perform(post(COMMENTS, UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", "Bearer " + tokens.issue(UUID.randomUUID())))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("the station list stays readable without signing in")
    void stationListIsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/stations").param("latitude", "48.77").param("longitude", "9.18"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a station's comments stay readable without signing in")
    void commentListIsPublic() throws Exception {
        mockMvc.perform(get(COMMENTS, UUID.randomUUID())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the operator directory stays readable without signing in")
    void operatorDirectoryIsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/operators")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Sign in with Apple is reachable without a bearer token")
    void appleSignInIsPublic() throws Exception {
        mockMvc.perform(post("/api/v1/auth/apple").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the sign-in endpoints of every provider are reachable without a bearer token")
    void signInFlowsArePublic() throws Exception {
        mockMvc.perform(get("/api/v1/auth/providers")).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/google/code").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/apple/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED).content("code=x"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the account endpoint needs a bearer token")
    void meNeedsToken() throws Exception {
        mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a valid token for a deleted account is treated as signed out")
    void deletedAccountTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tokens.issue(GONE))).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me").cookie(new Cookie(SessionCookie.NAME, tokens.issue(GONE)))).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the privacy policy link is public, while reporting and deleting an account need a token")
    void accountAreaEndpoints() throws Exception {
        UUID member = UUID.randomUUID();
        Cookie session = new Cookie(SessionCookie.NAME, tokens.issue(member));
        mockMvc.perform(get("/api/v1/legal")).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/comments/{id}/report", UUID.randomUUID())).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/comments/{id}/report", UUID.randomUUID()).header("Authorization", "Bearer " + tokens.issue(member)))
                .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/me")).andExpect(status().isUnauthorized());
        // Deleting an account from the web is a cookie write like any other: it needs the CSRF token.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/me").cookie(session))
                .andExpect(status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/me")
                .cookie(session, new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the moderation queue is admin-only")
    void moderationQueueIsAdminOnly() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reports")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/reports").header("Authorization", "Bearer " + tokens.issue(UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/reports").header("Authorization", "Bearer " + tokens.issue(ADMIN))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("favorites and station reports need a token, and the station report queue is admin-only")
    void favoritesAndStationReports() throws Exception {
        UUID station = UUID.randomUUID();
        String member = "Bearer " + tokens.issue(UUID.randomUUID());
        mockMvc.perform(get("/api/v1/me/favorites")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me/favorites").header("Authorization", member)).andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/me/favorites/{id}", station))
                .andExpect(status().isUnauthorized());
        // The report shares the public /stations/** prefix with the reads; only GET may pass without a token.
        mockMvc.perform(post("/api/v1/stations/{id}/reports", station)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/stations/{id}/reports", station).header("Authorization", member)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/station-reports")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/station-reports").header("Authorization", member)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/station-reports").header("Authorization", "Bearer " + tokens.issue(ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the admin area answers 401 without a token, 403 without the flag and 200 with it")
    void adminNeedsTheFlag() throws Exception {
        mockMvc.perform(get("/api/v1/admin/overview")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/overview").header("Authorization", "Bearer " + tokens.issue(UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/overview").header("Authorization", "Bearer " + tokens.issue(ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the session cookie authenticates reads, and an Authorization header overrides it")
    void sessionCookieAuthenticates() throws Exception {
        Cookie session = new Cookie(SessionCookie.NAME, tokens.issue(UUID.randomUUID()));
        mockMvc.perform(get("/api/v1/me").cookie(session)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me").cookie(session).header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me").cookie(new Cookie(SessionCookie.NAME, "forged"))).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a cookie-authenticated write needs the CSRF token, echoed as X-XSRF-TOKEN")
    void cookieWriteNeedsCsrfToken() throws Exception {
        Cookie session = new Cookie(SessionCookie.NAME, tokens.issue(UUID.randomUUID()));
        mockMvc.perform(post(COMMENTS, UUID.randomUUID()).cookie(session).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(COMMENTS, UUID.randomUUID()).cookie(session, new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());
        mockMvc.perform(post(COMMENTS, UUID.randomUUID()).cookie(session, new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "other")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("sign-in exchanges are exempt from CSRF even when a stale session cookie is present")
    void signInIsExempt() throws Exception {
        mockMvc.perform(post("/api/v1/auth/google/code").cookie(new Cookie(SessionCookie.NAME, "stale"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("responses carry the CSRF cookie so the client has a token before its first write")
    void csrfCookieIsIssued() throws Exception {
        mockMvc.perform(get("/api/v1/operators")).andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    @DisplayName("logging out is a cookie write and needs the CSRF token too")
    void logoutNeedsCsrfToken() throws Exception {
        Cookie session = new Cookie(SessionCookie.NAME, tokens.issue(UUID.randomUUID()));
        mockMvc.perform(post("/api/v1/auth/logout").cookie(session)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/auth/logout").cookie(session, new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t"))
                .andExpect(status().isOk());
    }

    /** Only {@link #ADMIN} carries the flag. */
    static class Admins implements AdminAccounts, KnownAccounts {
        @Override
        public boolean exists(UUID accountId) {
            return !GONE.equals(accountId);
        }

        @Override
        public boolean isAdmin(UUID accountId) {
            return ADMIN.equals(accountId);
        }
    }

    @RestController
    static class Routes {
        @GetMapping("/api/v1/auth/providers")
        List<String> providers() {
            return List.of();
        }

        @PostMapping("/api/v1/auth/{provider}/code")
        String code(@PathVariable String provider) {
            return "{}";
        }

        @PostMapping("/api/v1/auth/apple/callback")
        String appleCallback() {
            return "";
        }

        @PostMapping("/api/v1/auth/logout")
        String logout() {
            return "";
        }

        @GetMapping("/api/v1/me")
        UUID me(@AuthenticationPrincipal CurrentUser user) {
            return user.accountId();
        }

        @GetMapping("/api/v1/legal")
        String legal() {
            return "{}";
        }

        @org.springframework.web.bind.annotation.PostMapping("/api/v1/comments/{id}/report")
        String report(@PathVariable UUID id) {
            return "";
        }

        @org.springframework.web.bind.annotation.DeleteMapping("/api/v1/me")
        String deleteMe() {
            return "";
        }

        @GetMapping("/api/v1/admin/reports")
        String reports() {
            return "[]";
        }

        @GetMapping("/api/v1/me/favorites")
        List<String> favorites() {
            return List.of();
        }

        @org.springframework.web.bind.annotation.PutMapping("/api/v1/me/favorites/{stationId}")
        String favorite(@PathVariable UUID stationId) {
            return "";
        }

        @PostMapping("/api/v1/stations/{stationId}/reports")
        String reportStation(@PathVariable UUID stationId) {
            return "";
        }

        @GetMapping("/api/v1/admin/station-reports")
        String stationReports() {
            return "[]";
        }

        @GetMapping("/api/v1/admin/overview")
        String overview() {
            return "{}";
        }

        @GetMapping("/api/v1/stations")
        List<String> stations() {
            return List.of();
        }

        @GetMapping(COMMENTS)
        List<String> comments(@PathVariable UUID stationId) {
            return List.of();
        }

        @PostMapping(COMMENTS)
        @ResponseStatus(HttpStatus.CREATED)
        UUID createComment(@PathVariable UUID stationId, @AuthenticationPrincipal CurrentUser user) {
            return user.accountId();
        }

        @GetMapping("/api/v1/operators")
        List<String> operators() {
            return List.of();
        }

        @PostMapping("/api/v1/auth/apple")
        String signIn() {
            return "{}";
        }
    }
}
