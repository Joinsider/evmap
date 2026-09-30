package de.joinside.evmap_service.api.auth;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The three code exchanges against recorded provider answers. What matters is what each one reports as
 * <em>verified</em>, since that alone decides account linking — and that a rejected code becomes a
 * {@link SignInFailedException} (401), never a 500.
 */
class CodeSignInTests {
    private static final String WEB = "https://evmap.example";

    private static AuthProperties properties(AuthProperties.AppleWeb apple) {
        return new AuthProperties(WEB, Duration.ofSeconds(5), new AuthProperties.Client("google-client", "google-secret"),
                new AuthProperties.Client("github-client", "github-secret"), apple);
    }

    private static final AuthProperties PROPERTIES = properties(new AuthProperties.AppleWeb("", "", "", ""));

    @Nested
    class Google {
        private final TestKeys keys = new TestKeys();
        private final RestClient.Builder builder = RestClient.builder();
        private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        private final GoogleSignIn signIn = new GoogleSignIn(PROPERTIES, builder.build(), keys.decoder());

        private void answer(String idToken) {
            server.expect(requestTo(GoogleSignIn.TOKEN_ENDPOINT)).andExpect(method(HttpMethod.POST))
                    .andExpect(content().formDataContains(Map.of("code", "the-code", "code_verifier", "the-verifier",
                            "redirect_uri", WEB + "/auth/callback/google", "client_secret", "google-secret")))
                    .andRespond(withSuccess("{\"id_token\":\"" + idToken + "\"}", MediaType.APPLICATION_JSON));
        }

        @Test
        @DisplayName("takes subject, address and Google's verified flag from the ID token")
        void readsVerifiedIdentity() {
            answer(keys.idToken("https://accounts.google.com", "google-client", "g-1",
                    Map.of("email", "ada@example.org", "email_verified", true)));

            assertThat(signIn.exchange("the-code", "the-verifier"))
                    .isEqualTo(new VerifiedIdentity(Provider.GOOGLE, "g-1", "ada@example.org", true));
        }

        @Test
        @DisplayName("an address without email_verified is not verified")
        void missingVerifiedFlag() {
            answer(keys.idToken("accounts.google.com", "google-client", "g-1", Map.of("email", "ada@example.org")));

            assertThat(signIn.exchange("the-code", "the-verifier").emailVerified()).isFalse();
        }

        @Test
        @DisplayName("an ID token for another client is rejected")
        void wrongAudience() {
            answer(keys.idToken("https://accounts.google.com", "someone-else", "g-1", Map.of()));

            assertThatThrownBy(() -> signIn.exchange("the-code", "the-verifier")).isInstanceOf(SignInFailedException.class);
        }

        @Test
        @DisplayName("a code Google refuses is a failed sign-in")
        void refusedCode() {
            server.expect(requestTo(GoogleSignIn.TOKEN_ENDPOINT))
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":\"invalid_grant\"}").contentType(MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> signIn.exchange("the-code", "the-verifier")).isInstanceOf(SignInFailedException.class);
        }

        @Test
        @DisplayName("an answer without ID token is a failed sign-in")
        void missingIdToken() {
            server.expect(requestTo(GoogleSignIn.TOKEN_ENDPOINT)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> signIn.exchange("the-code", "the-verifier")).isInstanceOf(SignInFailedException.class);
        }

        @Test
        @DisplayName("offers the code flow with PKCE and the web callback")
        void authorization() {
            assertThat(signIn.authorization().pkce()).isTrue();
            assertThat(signIn.authorization().parameters()).containsEntry("redirect_uri", WEB + "/auth/callback/google")
                    .containsEntry("client_id", "google-client").doesNotContainKey("client_secret");
        }
    }

    @Nested
    class GitHub {
        private final RestClient.Builder builder = RestClient.builder();
        private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        private final GitHubSignIn signIn = new GitHubSignIn(PROPERTIES, builder.build());

        private void answer(String emails) {
            server.expect(requestTo(GitHubSignIn.TOKEN_ENDPOINT)).andExpect(method(HttpMethod.POST))
                    .andExpect(content().formDataContains(Map.of("code", "the-code", "code_verifier", "the-verifier")))
                    .andRespond(withSuccess("{\"access_token\":\"gho_x\",\"token_type\":\"bearer\"}", MediaType.APPLICATION_JSON));
            server.expect(requestTo(GitHubSignIn.USER_ENDPOINT)).andExpect(header("Authorization", "Bearer gho_x"))
                    .andRespond(withSuccess("{\"id\":583231,\"login\":\"octocat\"}", MediaType.APPLICATION_JSON));
            server.expect(requestTo(GitHubSignIn.EMAILS_ENDPOINT))
                    .andRespond(withSuccess(emails, MediaType.APPLICATION_JSON));
        }

        @Test
        @DisplayName("uses the numeric id as subject and the primary address with its verified flag")
        void readsPrimaryAddress() {
            answer("[{\"email\":\"old@example.org\",\"primary\":false,\"verified\":true},"
                    + "{\"email\":\"octo@example.org\",\"primary\":true,\"verified\":true}]");

            assertThat(signIn.exchange("the-code", "the-verifier"))
                    .isEqualTo(new VerifiedIdentity(Provider.GITHUB, "583231", "octo@example.org", true));
        }

        @Test
        @DisplayName("an unverified primary address is not verified, even if another one is")
        void unverifiedPrimary() {
            answer("[{\"email\":\"other@example.org\",\"primary\":false,\"verified\":true},"
                    + "{\"email\":\"octo@example.org\",\"primary\":true,\"verified\":false}]");

            assertThat(signIn.exchange("the-code", "the-verifier").emailVerified()).isFalse();
        }

        @Test
        @DisplayName("without a primary address the identity carries none")
        void noPrimaryAddress() {
            answer("[]");

            assertThat(signIn.exchange("the-code", "the-verifier"))
                    .isEqualTo(new VerifiedIdentity(Provider.GITHUB, "583231", null, false));
        }

        @Test
        @DisplayName("a failing user lookup is a failed sign-in")
        void userLookupFails() {
            server.expect(requestTo(GitHubSignIn.TOKEN_ENDPOINT))
                    .andRespond(withSuccess("{\"access_token\":\"gho_x\"}", MediaType.APPLICATION_JSON));
            server.expect(requestTo(GitHubSignIn.USER_ENDPOINT)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

            assertThatThrownBy(() -> signIn.exchange("the-code", "the-verifier")).isInstanceOf(SignInFailedException.class);
        }

        @Test
        @DisplayName("a refused token request is a failed sign-in")
        void tokenRequestFails() {
            server.expect(requestTo(GitHubSignIn.TOKEN_ENDPOINT)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

            assertThatThrownBy(() -> signIn.exchange("the-code", "the-verifier")).isInstanceOf(SignInFailedException.class);
        }

        @Test
        @DisplayName("offers the code flow with PKCE and the user:email scope")
        void authorization() {
            assertThat(signIn.enabled()).isTrue();
            assertThat(signIn.authorization().pkce()).isTrue();
            assertThat(signIn.authorization().parameters()).containsEntry("scope", "user:email")
                    .containsEntry("redirect_uri", WEB + "/auth/callback/github");
        }

        @Test
        @DisplayName("GitHub's 200-with-error answer to a bad code is a failed sign-in")
        void errorInBody() {
            server.expect(requestTo(GitHubSignIn.TOKEN_ENDPOINT))
                    .andRespond(withSuccess("{\"error\":\"bad_verification_code\"}", MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> signIn.exchange("the-code", "the-verifier"))
                    .isInstanceOf(SignInFailedException.class).hasMessageContaining("bad_verification_code");
        }
    }

    @Nested
    class AppleWeb {
        private final TestKeys keys = new TestKeys();
        private final RestClient.Builder builder = RestClient.builder();
        private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        @Test
        @DisplayName("signs a client secret with the Apple key and verifies the ID token for the Services ID")
        void exchangesCode() throws Exception {
            ECKey key = new ECKeyGenerator(Curve.P_256).generate();
            String pem = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder().encodeToString(key.toECPrivateKey().getEncoded()) + "\n-----END PRIVATE KEY-----";
            AuthProperties properties = properties(new AuthProperties.AppleWeb("de.joinside.evmap.web", "TEAM123", "KEY123", pem));
            AppleIdentityTokenVerifier verifier = new AppleIdentityTokenVerifier(keys.decoder(), "https://appleid.apple.com",
                    Set.of("de.joinside.EVMap", "de.joinside.evmap.web"));
            Instant now = Instant.parse("2026-09-29T10:00:00Z");
            AppleWebSignIn signIn = new AppleWebSignIn(properties, builder.build(), verifier, Clock.fixed(now, ZoneOffset.UTC));
            var sentForm = new java.util.concurrent.atomic.AtomicReference<String>();

            server.expect(requestTo(AppleWebSignIn.TOKEN_ENDPOINT))
                    .andExpect(content().formDataContains(Map.of("client_id", "de.joinside.evmap.web",
                            "redirect_uri", WEB + "/api/v1/auth/apple/callback")))
                    .andExpect(request -> sentForm.set(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString()))
                    .andRespond(withSuccess("{\"id_token\":\"" + keys.idToken("https://appleid.apple.com", "de.joinside.evmap.web", "a-1",
                            Map.of("email", "ada@example.org", "email_verified", "true")) + "\",\"refresh_token\":\"r-1\"}", MediaType.APPLICATION_JSON));

            // The refresh token rides along for revocation on account deletion (ADR 0020), tagged with the Services ID.
            assertThat(signIn.exchange("the-code", null))
                    .isEqualTo(new VerifiedIdentity(Provider.APPLE, "a-1", "ada@example.org", true,
                            new ProviderRefreshToken("r-1", "de.joinside.evmap.web")));

            String secret = java.net.URLDecoder.decode(sentForm.get().replaceAll(".*client_secret=([^&]*).*", "$1"),
                    java.nio.charset.StandardCharsets.UTF_8);
            SignedJWT jwt = SignedJWT.parse(secret);
            assertThat(jwt.verify(new ECDSAVerifier(key.toECPublicKey()))).isTrue();
            assertThat(jwt.getHeader().getKeyID()).isEqualTo("KEY123");
            assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("TEAM123");
            assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("de.joinside.evmap.web");
            assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("https://appleid.apple.com");
            assertThat(signIn.authorization().pkce()).isFalse();
            assertThat(signIn.authorization().parameters()).containsEntry("response_mode", "form_post");
        }
    }

    @Test
    @DisplayName("Apple web: a refused code or an answer without ID token is a failed sign-in")
    void appleWebFailures() throws Exception {
        ECKey key = new ECKeyGenerator(Curve.P_256).generate();
        String pem = "-----BEGIN PRIVATE KEY-----\\n" + Base64.getEncoder().encodeToString(key.toECPrivateKey().getEncoded())
                + "\\n-----END PRIVATE KEY-----";
        AuthProperties properties = properties(new AuthProperties.AppleWeb("svc", "TEAM", "KEY", pem));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AppleWebSignIn signIn = new AppleWebSignIn(properties, builder.build(),
                new AppleIdentityTokenVerifier(new TestKeys().decoder(), "https://appleid.apple.com", Set.of("svc")), Clock.systemUTC());
        assertThat(signIn.enabled()).isTrue();

        server.expect(requestTo(AppleWebSignIn.TOKEN_ENDPOINT)).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> signIn.exchange("c", null)).isInstanceOf(SignInFailedException.class);

        server.reset();
        server.expect(requestTo(AppleWebSignIn.TOKEN_ENDPOINT)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> signIn.exchange("c", null)).isInstanceOf(SignInFailedException.class);

        server.reset();
        server.expect(requestTo(AppleWebSignIn.TOKEN_ENDPOINT)).andRespond(withSuccess("{\"id_token\":\"not-a-jwt\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> signIn.exchange("c", null)).isInstanceOf(SignInFailedException.class);
    }

    @Test
    @DisplayName("a malformed Apple key fails with a message that does not contain it")
    void badAppleKey() {
        AuthProperties.AppleWeb apple = new AuthProperties.AppleWeb("svc", "TEAM", "KEY", "not-a-key");
        assertThatThrownBy(() -> AppleClientSecret.create(apple, java.time.Instant.now()))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("not-a-key");
    }

    @Test
    @DisplayName("redirect URIs ignore a trailing slash on the web base URL")
    void redirectUris() {
        AuthProperties slash = new AuthProperties(WEB + "/", Duration.ofSeconds(5), new AuthProperties.Client("", ""),
                new AuthProperties.Client("", ""), new AuthProperties.AppleWeb("", "", "", ""));
        assertThat(slash.redirectUri(Provider.GOOGLE)).isEqualTo(WEB + "/auth/callback/google");
        assertThat(slash.redirectUri(Provider.APPLE)).isEqualTo(WEB + "/api/v1/auth/apple/callback");
        assertThat(slash.clientRedirectUri(Provider.APPLE)).isEqualTo(WEB + "/auth/callback/apple");
        assertThat(Provider.fromToken("github")).contains(Provider.GITHUB);
        assertThat(Provider.fromToken("facebook")).isEmpty();
    }

    @Test
    @DisplayName("Apple identity tokens for an unknown audience are rejected")
    void appleAudience() {
        TestKeys keys = new TestKeys();
        AppleIdentityTokenVerifier verifier = new AppleIdentityTokenVerifier(keys.decoder(), "https://appleid.apple.com", Set.of("de.joinside.EVMap"));

        assertThat(verifier.verify(keys.idToken("https://appleid.apple.com", "de.joinside.EVMap", "a-1", Map.of("email_verified", true))).subject())
                .isEqualTo("a-1");
        assertThatThrownBy(() -> verifier.verify(keys.idToken("https://appleid.apple.com", "other.app", "a-1", Map.of())))
                .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
    }

    @Test
    @DisplayName("a provider without credentials is switched off")
    void disabledWithoutCredentials() {
        AuthProperties none = new AuthProperties(WEB, Duration.ofSeconds(5), new AuthProperties.Client("", ""),
                new AuthProperties.Client("id", ""), new AuthProperties.AppleWeb("", "", "", ""));
        assertThat(none.google().enabled()).isFalse();
        assertThat(none.github().enabled()).isFalse();
        assertThat(none.appleWeb().enabled()).isFalse();
    }
}
