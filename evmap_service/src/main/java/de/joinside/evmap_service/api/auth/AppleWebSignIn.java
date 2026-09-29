package de.joinside.evmap_service.api.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.util.Map;

/**
 * Sign in with Apple on the web. The iOS app keeps the native flow ({@code POST /api/v1/auth/apple});
 * this is for browsers.
 * <p>
 * Two differences from Google and GitHub: Apple does not support PKCE, so {@code pkce} is false and
 * the client's {@code state} check is what binds the answer to the browser that asked; and requesting
 * the e-mail scope forces {@code response_mode=form_post}, so the redirect URI is a backend endpoint
 * that relays code and state to the web route (see {@link AuthController}).
 */
@Component
@EnableConfigurationProperties(AuthProperties.class)
class AppleWebSignIn implements CodeSignIn {
    private static final Logger log = LoggerFactory.getLogger(AppleWebSignIn.class);

    static final String AUTHORIZATION_ENDPOINT = "https://appleid.apple.com/auth/authorize";
    static final String TOKEN_ENDPOINT = "https://appleid.apple.com/auth/token";

    private final AuthProperties properties;
    private final RestClient http;
    private final AppleIdentityTokenVerifier identityTokens;
    private final Clock clock;

    @Autowired
    AppleWebSignIn(AuthProperties properties, RestClient.Builder builder, AppleIdentityTokenVerifier identityTokens) {
        this(properties, OAuthClients.build(builder, properties), identityTokens, Clock.systemUTC());
    }

    /** Test seam. */
    AppleWebSignIn(AuthProperties properties, RestClient http, AppleIdentityTokenVerifier identityTokens, Clock clock) {
        this.properties = properties;
        this.http = http;
        this.identityTokens = identityTokens;
        this.clock = clock;
    }

    @Override
    public Provider provider() {
        return Provider.APPLE;
    }

    @Override
    public boolean enabled() {
        return properties.appleWeb().enabled();
    }

    @Override
    public Authorization authorization() {
        return new Authorization(provider().token(), AUTHORIZATION_ENDPOINT, Map.of(
                "client_id", properties.appleWeb().servicesId(),
                "redirect_uri", properties.redirectUri(provider()),
                "response_type", "code",
                "response_mode", "form_post",
                "scope", "email"), false);
    }

    @Override
    public VerifiedIdentity exchange(String code, String codeVerifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("client_id", properties.appleWeb().servicesId());
        form.add("client_secret", AppleClientSecret.create(properties.appleWeb(), clock.instant()));
        form.add("redirect_uri", properties.redirectUri(provider()));
        TokenResponse response;
        try {
            response = http.post().uri(TOKEN_ENDPOINT).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON).body(form).retrieve().body(TokenResponse.class);
        } catch (RestClientException exception) {
            log.warn("Apple rejected the authorization code: {}", exception.getMessage());
            throw new SignInFailedException("Apple rejected the authorization code", exception);
        }
        if (response == null || response.id_token() == null) throw new SignInFailedException("Apple returned no ID token");
        try {
            VerifiedIdentity identity = identityTokens.verify(response.id_token());
            // Kept for revocation when the account is deleted (ADR 0020); AccountService drops it if
            // no encryption key is configured.
            return response.refresh_token() == null ? identity
                    : identity.withRefreshToken(new ProviderRefreshToken(response.refresh_token(), properties.appleWeb().servicesId()));
        } catch (JwtException exception) {
            throw new SignInFailedException("Apple ID token rejected", exception);
        }
    }

    @SuppressWarnings("java:S116") // Field names are Apple's wire format.
    record TokenResponse(String id_token, String refresh_token) {
    }
}
