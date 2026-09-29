package de.joinside.evmap_service.api.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * GitHub: plain OAuth 2 with PKCE — there is no OpenID Connect for users, which is why ADR 0018
 * exchanges codes in the backend at all. The subject is the numeric user id (a login can be renamed);
 * the address is the <em>primary</em> one and counts as verified only if GitHub says so.
 */
@Component
@EnableConfigurationProperties(AuthProperties.class)
class GitHubSignIn implements CodeSignIn {
    private static final Logger log = LoggerFactory.getLogger(GitHubSignIn.class);

    static final String AUTHORIZATION_ENDPOINT = "https://github.com/login/oauth/authorize";
    static final String TOKEN_ENDPOINT = "https://github.com/login/oauth/access_token";
    static final String USER_ENDPOINT = "https://api.github.com/user";
    static final String EMAILS_ENDPOINT = "https://api.github.com/user/emails";

    private final AuthProperties properties;
    private final RestClient http;

    @Autowired
    GitHubSignIn(AuthProperties properties, RestClient.Builder builder) {
        this(properties, OAuthClients.build(builder, properties));
    }

    /** Test seam. */
    GitHubSignIn(AuthProperties properties, RestClient http) {
        this.properties = properties;
        this.http = http;
    }

    @Override
    public Provider provider() {
        return Provider.GITHUB;
    }

    @Override
    public boolean enabled() {
        return properties.github().enabled();
    }

    @Override
    public Authorization authorization() {
        return new Authorization(provider().token(), AUTHORIZATION_ENDPOINT, Map.of(
                "client_id", properties.github().clientId(),
                "redirect_uri", properties.redirectUri(provider()),
                "response_type", "code",
                // user:email is what exposes the verified flag; the profile itself needs no scope.
                "scope", "user:email",
                "allow_signup", "true"), true);
    }

    @Override
    public VerifiedIdentity exchange(String code, String codeVerifier) {
        String accessToken = accessToken(code, codeVerifier);
        try {
            User user = http.get().uri(USER_ENDPOINT).headers(headers -> headers.setBearerAuth(accessToken))
                    .accept(MediaType.APPLICATION_JSON).retrieve().body(User.class);
            List<Email> emails = http.get().uri(EMAILS_ENDPOINT).headers(headers -> headers.setBearerAuth(accessToken))
                    .accept(MediaType.APPLICATION_JSON).retrieve().body(new ParameterizedTypeReference<>() {
                    });
            if (user == null || user.id() == null) throw new SignInFailedException("GitHub returned no user");
            Email primary = emails == null ? null : emails.stream().filter(Email::primary).findFirst().orElse(null);
            return new VerifiedIdentity(provider(), user.id().toString(), primary == null ? null : primary.email(),
                    primary != null && primary.verified());
        } catch (RestClientException exception) {
            log.warn("GitHub user lookup failed: {}", exception.getMessage());
            throw new SignInFailedException("GitHub user lookup failed", exception);
        }
    }

    private String accessToken(String code, String codeVerifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.github().clientId());
        form.add("client_secret", properties.github().clientSecret());
        form.add("code", code);
        form.add("code_verifier", codeVerifier);
        form.add("redirect_uri", properties.redirectUri(provider()));
        TokenResponse response;
        try {
            response = http.post().uri(TOKEN_ENDPOINT).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON).body(form).retrieve().body(TokenResponse.class);
        } catch (RestClientException exception) {
            log.warn("GitHub token exchange failed: {}", exception.getMessage());
            throw new SignInFailedException("GitHub token exchange failed", exception);
        }
        // GitHub answers a bad code with 200 and an error field rather than an error status.
        if (response == null || response.access_token() == null) {
            String error = response == null ? "empty response" : response.error();
            log.warn("GitHub rejected the authorization code: {}", error);
            throw new SignInFailedException("GitHub rejected the authorization code: " + error);
        }
        return response.access_token();
    }

    @SuppressWarnings("java:S116") // Field names are GitHub's wire format.
    record TokenResponse(String access_token, String error) {
    }

    record User(Long id) {
    }

    record Email(String email, boolean primary, boolean verified) {
    }
}
