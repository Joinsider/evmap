package de.joinside.evmap_service.api.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;

@Service
class AppleIdentityTokenVerifier {
    private static final Logger log = LoggerFactory.getLogger(AppleIdentityTokenVerifier.class);

    private final NimbusJwtDecoder decoder;

    AppleIdentityTokenVerifier(@Value("${evmap.apple.jwks-uri}") String jwksUri, @Value("${evmap.apple.issuer}") String issuer,
                               @Value("${evmap.apple.audience}") String audience) {
        log.info("Apple identity tokens verified against {} (issuer {}, audience {})", jwksUri, issuer,
                audience.isBlank() ? "<any — APPLE_CLIENT_ID not set>" : audience);
        decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), token ->
                audience.isBlank() || token.getAudience().contains(audience) ? org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success() :
                        org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(new org.springframework.security.oauth2.core.OAuth2Error("invalid_audience"))));
    }

    String subject(String identityToken) {
        try {
            Jwt jwt = decoder.decode(identityToken);
            return jwt.getSubject();
        } catch (JwtException exception) {
            // The token itself stays out of the log; the reason is what makes client bugs debuggable.
            log.warn("Apple identity token rejected: {}", exception.getMessage());
            throw exception;
        }
    }
}
