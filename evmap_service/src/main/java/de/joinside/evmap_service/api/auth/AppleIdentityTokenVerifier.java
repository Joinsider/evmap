package de.joinside.evmap_service.api.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;

@Service
class AppleIdentityTokenVerifier {
    private final NimbusJwtDecoder decoder;

    AppleIdentityTokenVerifier(@Value("${evmap.apple.jwks-uri}") String jwksUri, @Value("${evmap.apple.issuer}") String issuer,
                               @Value("${evmap.apple.audience}") String audience) {
        decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), token ->
                audience.isBlank() || token.getAudience().contains(audience) ? org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success() :
                        org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(new org.springframework.security.oauth2.core.OAuth2Error("invalid_audience"))));
    }

    String subject(String identityToken) {
        Jwt jwt = decoder.decode(identityToken);
        return jwt.getSubject();
    }
}
