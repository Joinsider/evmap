package de.joinside.evmap_service.api.auth;

import de.joinside.evmap_service.api.security.AccessTokenService;
import de.joinside.evmap_service.api.security.SessionCookie;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AppleIdentityTokenVerifier apple;
    private final AppleTokens appleTokens;
    private final List<CodeSignIn> codeSignIns;
    private final AccountService accounts;
    private final AccessTokenService tokens;
    private final AuthProperties properties;
    private final SessionCookie sessionCookie;

    AuthController(AppleIdentityTokenVerifier apple, AppleTokens appleTokens, List<CodeSignIn> codeSignIns, AccountService accounts,
                   AccessTokenService tokens, AuthProperties properties, SessionCookie sessionCookie) {
        this.apple = apple;
        this.appleTokens = appleTokens;
        this.codeSignIns = codeSignIns;
        this.accounts = accounts;
        this.tokens = tokens;
        this.properties = properties;
        this.sessionCookie = sessionCookie;
    }

    /**
     * Native Sign in with Apple from the iOS app: the identity token is already the proof. The
     * authorization code that comes with it is optional and only buys a refresh token to revoke when
     * the account is deleted (ADR 0020); without it, or if Apple refuses it, sign-in works all the same.
     */
    @PostMapping("/apple")
    AccessTokenResponse apple(@RequestBody AppleLoginRequest request) {
        log.debug("Apple sign-in requested");
        VerifiedIdentity identity = apple.verify(request.identityToken());
        ProviderRefreshToken refreshToken = appleTokens.redeemNativeCode(request.authorizationCode(), identity).orElse(null);
        return issue(refreshToken == null ? identity : identity.withRefreshToken(refreshToken));
    }

    /** The code flows this deployment has credentials for, in a fixed order, for clients to offer. */
    @GetMapping("/providers")
    List<CodeSignIn.Authorization> providers() {
        return codeSignIns.stream().filter(CodeSignIn::enabled)
                .sorted((left, right) -> left.provider().compareTo(right.provider()))
                .map(CodeSignIn::authorization).toList();
    }

    /**
     * The web sign-in. The token goes out only as the {@code HttpOnly} session cookie, never in the
     * body, so the page's scripts never see it.
     */
    @PostMapping("/{provider}/code")
    ResponseEntity<Void> code(@PathVariable String provider, @RequestBody CodeRequest request) {
        CodeSignIn signIn = codeSignIns.stream().filter(CodeSignIn::enabled)
                .filter(candidate -> candidate.provider().token().equals(provider)).findFirst()
                .orElseThrow(UnknownProviderException::new);
        if (request.code() == null || request.code().isBlank()) throw new IllegalArgumentException("Missing authorization code");
        if (signIn.authorization().pkce() && (request.codeVerifier() == null || request.codeVerifier().isBlank()))
            throw new IllegalArgumentException("Missing PKCE code verifier");
        log.debug("{} code sign-in requested", provider);
        String token = issue(signIn.exchange(request.code(), request.codeVerifier())).accessToken();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, sessionCookie.issue(token).toString()).build();
    }

    /** Ends the web session: the cookie is HttpOnly, so only the server can remove it. */
    @PostMapping("/logout")
    ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, sessionCookie.clear().toString()).build();
    }

    /**
     * Apple's {@code form_post} answer, relayed to the web route as query parameters. The browser then
     * posts the code to {@link #code} like for every other provider, so the client has one flow. Code and
     * state are passed on untouched: the state check belongs to the client that generated it.
     */
    @PostMapping(value = "/apple/callback", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<Void> appleCallback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                                       @RequestParam(required = false) String error) {
        UriComponentsBuilder target = UriComponentsBuilder.fromUriString(properties.clientRedirectUri(Provider.APPLE));
        if (code != null) target.queryParam("code", code);
        if (state != null) target.queryParam("state", state);
        if (error != null) target.queryParam("error", error);
        return ResponseEntity.status(HttpStatus.SEE_OTHER).header(HttpHeaders.LOCATION, target.encode().build().toUriString()).build();
    }

    private AccessTokenResponse issue(VerifiedIdentity identity) {
        UUID account = accounts.signIn(identity);
        log.info("Issued access token for account {} (provider {})", account, identity.provider().token());
        return new AccessTokenResponse(tokens.issue(account));
    }

    record AppleLoginRequest(@NotBlank String identityToken, String authorizationCode) {
    }

    record CodeRequest(String code, String codeVerifier) {
    }

    record AccessTokenResponse(String accessToken) {
    }
}
