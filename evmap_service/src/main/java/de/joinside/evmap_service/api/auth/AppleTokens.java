package de.joinside.evmap_service.api.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.util.Optional;

/**
 * The two Apple calls account deletion depends on (ADR 0020): redeeming the native app's
 * authorization code for a refresh token at sign-in, and revoking that token when the account goes.
 * The web flow gets its refresh token from {@link AppleWebSignIn}'s own exchange.
 * <p>
 * Every failure here is contained: a sign-in must not fail because Apple refused to hand out a refresh
 * token, and an erasure must not fail because Apple is unreachable. Both are logged as warnings without
 * the response body.
 */
@Component
class AppleTokens {
    private static final Logger log = LoggerFactory.getLogger(AppleTokens.class);

    static final String REVOKE_ENDPOINT = "https://appleid.apple.com/auth/revoke";

    private final AuthProperties properties;
    private final RestClient http;
    private final TokenCipher cipher;
    private final AppleIdentityTokenVerifier identityTokens;
    private final String nativeClientId;
    private final Clock clock;

    @Autowired
    AppleTokens(AuthProperties properties, RestClient.Builder builder, TokenCipher cipher, AppleIdentityTokenVerifier identityTokens,
                @Value("${evmap.apple.audience:}") String nativeClientId) {
        this(properties, OAuthClients.build(builder, properties), cipher, identityTokens, nativeClientId, Clock.systemUTC());
    }

    /** Test seam. */
    AppleTokens(AuthProperties properties, RestClient http, TokenCipher cipher, AppleIdentityTokenVerifier identityTokens,
                String nativeClientId, Clock clock) {
        this.properties = properties;
        this.http = http;
        this.cipher = cipher;
        this.identityTokens = identityTokens;
        this.nativeClientId = nativeClientId;
        this.clock = clock;
    }

    /** Whether a refresh token can be obtained now and kept for later: a key to encrypt with, and one to sign with. */
    boolean canKeepTokens() {
        return cipher.enabled() && properties.appleWeb().canSign();
    }

    /**
     * Redeems the authorization code the iOS app got alongside its identity token. The refresh token is
     * returned only if Apple's answer is for the <em>same</em> Apple user as {@code identity}: the code
     * comes from the client, and pairing it with somebody else's identity would attach their token.
     */
    Optional<ProviderRefreshToken> redeemNativeCode(String authorizationCode, VerifiedIdentity identity) {
        if (authorizationCode == null || authorizationCode.isBlank() || nativeClientId.isBlank() || !canKeepTokens()) return Optional.empty();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", authorizationCode);
        form.add("client_id", nativeClientId);
        form.add("client_secret", AppleClientSecret.create(properties.appleWeb(), nativeClientId, clock.instant()));
        try {
            AppleWebSignIn.TokenResponse response = http.post().uri(AppleWebSignIn.TOKEN_ENDPOINT)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).accept(MediaType.APPLICATION_JSON).body(form)
                    .retrieve().body(AppleWebSignIn.TokenResponse.class);
            if (response == null || response.refresh_token() == null || response.id_token() == null) return Optional.empty();
            if (!identityTokens.verify(response.id_token()).subject().equals(identity.subject())) {
                log.warn("Apple authorization code belongs to a different user than the identity token; ignoring it");
                return Optional.empty();
            }
            return Optional.of(new ProviderRefreshToken(response.refresh_token(), nativeClientId));
        } catch (RestClientException | JwtException | IllegalStateException exception) {
            log.warn("Could not redeem the Apple authorization code for a refresh token: {}", exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Revokes a stored token at Apple. Returns whether Apple accepted it; an unusable stored value or an
     * unreachable Apple only costs the revocation, never the deletion.
     */
    boolean revoke(String storedToken, String clientId) {
        if (!properties.appleWeb().canSign() || !cipher.enabled()) {
            log.warn("An Apple refresh token is stored but revocation is not configured; leaving it unrevoked");
            return false;
        }
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("client_id", clientId);
            form.add("client_secret", AppleClientSecret.create(properties.appleWeb(), clientId, clock.instant()));
            form.add("token", cipher.decrypt(storedToken));
            form.add("token_type_hint", "refresh_token");
            http.post().uri(REVOKE_ENDPOINT).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().toBodilessEntity();
            return true;
        } catch (RestClientException | IllegalStateException exception) {
            log.warn("Could not revoke an Apple refresh token: {}", exception.getClass().getSimpleName());
            return false;
        }
    }
}
