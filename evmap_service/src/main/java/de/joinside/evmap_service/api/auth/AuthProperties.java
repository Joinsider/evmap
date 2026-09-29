package de.joinside.evmap_service.api.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * The web sign-in flows (ADR 0018). A provider whose credentials are blank is switched off: it is
 * left out of {@code GET /api/v1/auth/providers}, so clients do not offer it, and its code endpoint
 * answers 404. That keeps a local stack and CI working without any OAuth app.
 *
 * @param webBaseUrl origin of the web client, e.g. {@code https://evmap.example}. The redirect URIs
 *                   registered with every provider derive from it, and the iOS app's HTTPS callback
 *                   uses the same ones through its associated domain.
 * @param timeout    connect and read timeout per call to a provider
 */
@ConfigurationProperties("evmap.auth")
record AuthProperties(@DefaultValue("http://localhost:4200") String webBaseUrl,
                      @DefaultValue("10s") Duration timeout,
                      @DefaultValue Client google,
                      @DefaultValue Client github,
                      @DefaultValue AppleWeb appleWeb) {

    /** An OAuth client registered with Google or GitHub. */
    record Client(@DefaultValue("") String clientId, @DefaultValue("") String clientSecret) {
        boolean enabled() {
            return !clientId.isBlank() && !clientSecret.isBlank();
        }
    }

    /**
     * Sign in with Apple on the web: a Services ID plus the key Apple issues for it. Apple has no fixed
     * client secret; {@link AppleClientSecret} signs a short-lived one with this key.
     *
     * @param privateKey the {@code .p8} key's PEM text (PKCS#8), newlines as-is or as {@code \n}
     */
    record AppleWeb(@DefaultValue("") String servicesId, @DefaultValue("") String teamId,
                    @DefaultValue("") String keyId, @DefaultValue("") String privateKey) {
        boolean enabled() {
            return !servicesId.isBlank() && !teamId.isBlank() && !keyId.isBlank() && !privateKey.isBlank();
        }
    }

    String redirectUri(Provider provider) {
        String base = webBaseUrl.endsWith("/") ? webBaseUrl.substring(0, webBaseUrl.length() - 1) : webBaseUrl;
        // Apple answers with a form POST once e-mail is requested, which only a server can receive;
        // AuthController relays it to the web route the other providers redirect to directly.
        return provider == Provider.APPLE ? base + "/api/v1/auth/apple/callback" : base + "/auth/callback/" + provider.token();
    }

    String clientRedirectUri(Provider provider) {
        String base = webBaseUrl.endsWith("/") ? webBaseUrl.substring(0, webBaseUrl.length() - 1) : webBaseUrl;
        return base + "/auth/callback/" + provider.token();
    }
}
