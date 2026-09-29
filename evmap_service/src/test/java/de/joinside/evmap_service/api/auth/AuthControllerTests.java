package de.joinside.evmap_service.api.auth;

import de.joinside.evmap_service.api.security.AccessTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Routing and input checks of the sign-in endpoints; the exchanges themselves are in {@link CodeSignInTests}. */
class AuthControllerTests {
    private final CodeSignIn google = provider(Provider.GOOGLE, true, true);
    private final CodeSignIn github = provider(Provider.GITHUB, false, true);
    private final CodeSignIn apple = provider(Provider.APPLE, true, false);
    private final AccountService accounts = mock(AccountService.class);
    private final AuthProperties properties = new AuthProperties("https://evmap.example", Duration.ofSeconds(5),
            new AuthProperties.Client("", ""), new AuthProperties.Client("", ""), new AuthProperties.AppleWeb("", "", "", ""));

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AuthController(mock(AppleIdentityTokenVerifier.class), List.of(github, apple, google), accounts,
                    new AccessTokenService("test-secret-with-sufficient-length", Duration.ofHours(1)), properties))
            .setControllerAdvice(new de.joinside.evmap_service.api.ApiExceptionHandlerAccess().handler())
            .build();

    private static CodeSignIn provider(Provider provider, boolean enabled, boolean pkce) {
        CodeSignIn signIn = mock(CodeSignIn.class);
        when(signIn.provider()).thenReturn(provider);
        when(signIn.enabled()).thenReturn(enabled);
        when(signIn.authorization()).thenReturn(new CodeSignIn.Authorization(provider.token(), "https://idp/" + provider.token(), Map.of(), pkce));
        return signIn;
    }

    @Test
    @DisplayName("lists only configured providers, Apple first")
    void listsEnabledProviders() throws Exception {
        mockMvc.perform(get("/api/v1/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].provider").value("apple"))
                .andExpect(jsonPath("$[1].provider").value("google"));
    }

    @Test
    @DisplayName("exchanges a code and answers with an access token")
    void exchangesCode() throws Exception {
        when(google.exchange("c", "v")).thenReturn(new VerifiedIdentity(Provider.GOOGLE, "g-1", null, false));
        when(accounts.signIn(any())).thenReturn(java.util.UUID.randomUUID());

        mockMvc.perform(post("/api/v1/auth/google/code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"c\",\"codeVerifier\":\"v\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString());
    }

    @Test
    @DisplayName("a PKCE provider without a code verifier is a bad request, not an exchange")
    void requiresVerifier() throws Exception {
        mockMvc.perform(post("/api/v1/auth/google/code").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"c\"}"))
                .andExpect(status().isBadRequest());
        verify(google, never()).exchange(any(), any());
    }

    @Test
    @DisplayName("a switched-off or unknown provider answers 404")
    void unknownProvider() throws Exception {
        mockMvc.perform(post("/api/v1/auth/github/code").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"c\",\"codeVerifier\":\"v\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/auth/facebook/code").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"c\",\"codeVerifier\":\"v\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a code the provider rejects answers 401")
    void rejectedCode() throws Exception {
        when(google.exchange("c", "v")).thenThrow(new SignInFailedException("nope"));

        mockMvc.perform(post("/api/v1/auth/google/code").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"c\",\"codeVerifier\":\"v\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a missing code is a bad request")
    void requiresCode() throws Exception {
        mockMvc.perform(post("/api/v1/auth/google/code").contentType(MediaType.APPLICATION_JSON).content("{\"codeVerifier\":\"v\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("relays an Apple error to the web route")
    void relaysAppleError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/apple/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("error", "user_cancelled_authorize").param("state", "s"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "https://evmap.example/auth/callback/apple?state=s&error=user_cancelled_authorize"));
    }

    @Test
    @DisplayName("relays Apple's form_post to the web callback route")
    void relaysAppleFormPost() throws Exception {
        mockMvc.perform(post("/api/v1/auth/apple/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("code", "c/1").param("state", "s").param("user", "{\"name\":{}}"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "https://evmap.example/auth/callback/apple?code=c/1&state=s"));
    }
}
