package de.joinside.evmap_service.api.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;
import java.util.Set;

/**
 * Google: OpenID Connect code flow with PKCE. The ID token from the token endpoint carries
 * {@code sub}, {@code email} and {@code email_verified}; it is checked against Google's keys even
 * though it arrived over TLS straight from Google, so a misconfigured endpoint cannot slip a token
 * past us.
 */
@Component
@EnableConfigurationProperties(AuthProperties.class)
class GoogleSignIn implements CodeSignIn {
    private static final Logger log = LoggerFactory.getLogger(GoogleSignIn.class);

    static final String AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    static final String JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";
    /** Google issues both spellings. */
    private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    private final AuthProperties properties;
    private final RestClient http;
    private final NimbusJwtDecoder idTokens;

    @Autowired
    GoogleSignIn(AuthProperties properties, RestClient.Builder builder) {
        this(properties, OAuthClients.build(builder, properties), NimbusJwtDecoder.withJwkSetUri(JWKS_URI).build());
    }

    /** Test seam: a prepared client and a decoder over a local key. */
    GoogleSignIn(AuthProperties properties, RestClient http, NimbusJwtDecoder idTokens) {
        this.properties = properties;
        this.http = http;
        String clientId = properties.google().clientId();
        idTokens.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), token ->
                ISSUERS.contains(String.valueOf(token.getClaimAsString("iss"))) && token.getAudience().contains(clientId)
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "issuer or audience mismatch", null))));
        this.idTokens = idTokens;
    }

    @Override
    public Provider provider() {
        return Provider.GOOGLE;
    }

    @Override
    public boolean enabled() {
        return properties.google().enabled();
    }

    @Override
    public Authorization authorization() {
        return new Authorization(provider().token(), AUTHORIZATION_ENDPOINT, Map.of(
                "client_id", properties.google().clientId(),
                "redirect_uri", properties.redirectUri(provider()),
                "response_type", "code",
                "scope", "openid email",
                // Always show the account chooser, otherwise a shared browser signs in whoever was last.
                "prompt", "select_account"), true);
    }

    @Override
    public VerifiedIdentity exchange(String code, String codeVerifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("code_verifier", codeVerifier);
        form.add("client_id", properties.google().clientId());
        form.add("client_secret", properties.google().clientSecret());
        form.add("redirect_uri", properties.redirectUri(provider()));
        TokenResponse response;
        try {
            response = http.post().uri(TOKEN_ENDPOINT).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().body(TokenResponse.class);
        } catch (RestClientException exception) {
            log.warn("Google rejected the authorization code: {}", exception.getMessage());
            throw new SignInFailedException("Google rejected the authorization code", exception);
        }
        if (response == null || response.id_token() == null) throw new SignInFailedException("Google returned no ID token");
        try {
            Jwt idToken = idTokens.decode(response.id_token());
            return new VerifiedIdentity(provider(), idToken.getSubject(), idToken.getClaimAsString("email"),
                    Boolean.TRUE.equals(idToken.getClaimAsBoolean("email_verified")));
        } catch (JwtException exception) {
            log.warn("Google ID token rejected: {}", exception.getMessage());
            throw new SignInFailedException("Google ID token rejected", exception);
        }
    }

    @SuppressWarnings("java:S116") // Field names are Google's wire format.
    record TokenResponse(String id_token) {
    }
}
