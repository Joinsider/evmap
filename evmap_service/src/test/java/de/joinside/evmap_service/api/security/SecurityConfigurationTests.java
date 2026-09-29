package de.joinside.evmap_service.api.security;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public read paths and the comment write path share the {@code /api/v1/stations/**} prefix, so
 * a permit rule without an HTTP method opens the write too — the handler then meets a {@code null}
 * principal and answers 500. The real controllers' services are package-private to their own
 * packages, so stand-ins on the same routes are what reach the handler here: the filter chain is
 * the thing under test, not the controllers.
 */
@WebMvcTest(controllers = SecurityConfigurationTests.Routes.class)
@Import({SecurityConfiguration.class, AccessTokenService.class, SecurityConfigurationTests.Routes.class})
class SecurityConfigurationTests {

    private static final String COMMENTS = "/api/v1/stations/{stationId}/comments";
    private static final String BODY = "{\"body\":\"Works fine\"}";

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

    @RestController
    static class Routes {
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
            return user.identityId();
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
