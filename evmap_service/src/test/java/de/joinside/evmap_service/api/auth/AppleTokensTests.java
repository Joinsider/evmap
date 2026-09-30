package de.joinside.evmap_service.api.auth;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The two Apple calls behind account deletion (ADR 0020), against recorded answers. */
class AppleTokensTests {
    private static final String BUNDLE_ID = "de.joinside.EVMap";
    private static final String SERVICES_ID = "de.joinside.evmap.web";
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final VerifiedIdentity IDENTITY = new VerifiedIdentity(Provider.APPLE, "a-1", "ada@example.org", true);

    private final TestKeys keys = new TestKeys();
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final TokenCipher cipher = new TokenCipher(KEY);
    private AppleTokens tokens;

    @BeforeEach
    void setUp() throws Exception {
        ECKey key = new ECKeyGenerator(Curve.P_256).generate();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(key.toECPrivateKey().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        tokens = tokens(new AuthProperties.AppleWeb(SERVICES_ID, "TEAM123", "KEY123", pem), cipher, BUNDLE_ID);
    }

    private AppleTokens tokens(AuthProperties.AppleWeb apple, TokenCipher tokenCipher, String nativeClientId) {
        AuthProperties properties = new AuthProperties("https://evmap.example", Duration.ofSeconds(5),
                new AuthProperties.Client("", ""), new AuthProperties.Client("", ""), apple);
        return new AppleTokens(properties, builder.build(), tokenCipher,
                new AppleIdentityTokenVerifier(keys.decoder(), "https://appleid.apple.com", Set.of(BUNDLE_ID, SERVICES_ID)),
                nativeClientId, Clock.systemUTC());
    }

    private String idToken(String subject) {
        return keys.idToken("https://appleid.apple.com", BUNDLE_ID, subject, Map.of());
    }

    @Test
    @DisplayName("redeems the native code as the bundle id and returns the refresh token tagged with it")
    void redeemsNativeCode() {
        server.expect(requestTo(AppleWebSignIn.TOKEN_ENDPOINT)).andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(Map.of("code", "auth-code", "client_id", BUNDLE_ID, "grant_type", "authorization_code")))
                .andRespond(withSuccess("{\"id_token\":\"" + idToken("a-1") + "\",\"refresh_token\":\"r-1\"}", MediaType.APPLICATION_JSON));

        assertThat(tokens.redeemNativeCode("auth-code", IDENTITY)).contains(new ProviderRefreshToken("r-1", BUNDLE_ID));
        server.verify();
    }

    @Test
    @DisplayName("a code that belongs to a different Apple user is discarded")
    void rejectsForeignCode() {
        server.expect(requestTo(AppleWebSignIn.TOKEN_ENDPOINT))
                .andRespond(withSuccess("{\"id_token\":\"" + idToken("somebody-else") + "\",\"refresh_token\":\"r-1\"}", MediaType.APPLICATION_JSON));

        assertThat(tokens.redeemNativeCode("auth-code", IDENTITY)).isEmpty();
    }

    @Test
    @DisplayName("a refused code, or no code at all, just means no refresh token")
    void refusedOrMissingCode() {
        server.expect(requestTo(AppleWebSignIn.TOKEN_ENDPOINT)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThat(tokens.redeemNativeCode("auth-code", IDENTITY)).isEmpty();
        assertThat(tokens.redeemNativeCode(null, IDENTITY)).isEmpty();
        assertThat(tokens.redeemNativeCode(" ", IDENTITY)).isEmpty();
    }

    @Test
    @DisplayName("does not even call Apple when there is no key to encrypt with")
    void offWithoutEncryptionKey() {
        AppleTokens off = tokens(new AuthProperties.AppleWeb(SERVICES_ID, "TEAM", "KEY", "pem"), new TokenCipher(""), BUNDLE_ID);
        server.expect(never(), requestTo(AppleWebSignIn.TOKEN_ENDPOINT)).andRespond(withSuccess());

        assertThat(off.canKeepTokens()).isFalse();
        assertThat(off.redeemNativeCode("auth-code", IDENTITY)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("revokes the decrypted token, naming the client id it was issued to")
    void revokes() {
        server.expect(requestTo(AppleTokens.REVOKE_ENDPOINT)).andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(Map.of("client_id", SERVICES_ID, "token", "r-1", "token_type_hint", "refresh_token")))
                .andRespond(withSuccess());

        assertThat(tokens.revoke(cipher.encrypt("r-1"), SERVICES_ID)).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("an unreachable Apple or an unreadable stored token costs the revocation, not the deletion")
    void revocationFailuresAreContained() {
        server.expect(requestTo(AppleTokens.REVOKE_ENDPOINT)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(tokens.revoke(cipher.encrypt("r-1"), SERVICES_ID)).isFalse();
        assertThat(tokens.revoke("v1:garbage", SERVICES_ID)).isFalse();
        assertThat(tokens(new AuthProperties.AppleWeb("", "", "", ""), cipher, BUNDLE_ID).revoke(cipher.encrypt("r-1"), SERVICES_ID)).isFalse();
    }
}
