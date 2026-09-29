package de.joinside.evmap_service.api.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Checks Apple identity tokens: from the iOS app (audience: the bundle id, {@code APPLE_CLIENT_ID})
 * and from the web flow's token exchange (audience: the Services ID, {@code APPLE_SERVICES_ID}).
 */
@Service
class AppleIdentityTokenVerifier {
    private static final Logger log = LoggerFactory.getLogger(AppleIdentityTokenVerifier.class);

    private final JwtDecoder decoder;

    @Autowired
    AppleIdentityTokenVerifier(@Value("${evmap.apple.jwks-uri}") String jwksUri, @Value("${evmap.apple.issuer}") String issuer,
                               @Value("${evmap.apple.audience}") String audience,
                               @Value("${evmap.auth.apple-web.services-id:}") String servicesId) {
        this(NimbusJwtDecoder.withJwkSetUri(jwksUri).build(), issuer, audiences(audience, servicesId));
        log.info("Apple identity tokens verified against {} (issuer {}, audience {})", jwksUri, issuer,
                audiences(audience, servicesId).isEmpty() ? "<any — APPLE_CLIENT_ID not set>" : audiences(audience, servicesId));
    }

    /** Test seam: a decoder over a local key instead of Apple's JWKS. */
    AppleIdentityTokenVerifier(NimbusJwtDecoder decoder, String issuer, Set<String> audiences) {
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), token ->
                audiences.isEmpty() || token.getAudience().stream().anyMatch(audiences::contains) ? OAuth2TokenValidatorResult.success() :
                        OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_audience"))));
        this.decoder = decoder;
    }

    private static Set<String> audiences(String... values) {
        return Stream.of(values).filter(value -> value != null && !value.isBlank()).collect(Collectors.toUnmodifiableSet());
    }

    VerifiedIdentity verify(String identityToken) {
        try {
            Jwt jwt = decoder.decode(identityToken);
            // Apple sends email_verified as the string "true" in some tokens and as a boolean in others.
            Object verified = jwt.getClaim("email_verified");
            return new VerifiedIdentity(Provider.APPLE, jwt.getSubject(), jwt.getClaimAsString("email"),
                    Boolean.TRUE.equals(verified) || "true".equals(verified));
        } catch (JwtException exception) {
            // The token itself stays out of the log; the reason is what makes client bugs debuggable.
            log.warn("Apple identity token rejected: {}", exception.getMessage());
            throw exception;
        }
    }
}
