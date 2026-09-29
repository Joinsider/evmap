package de.joinside.evmap_service.api.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Instant;
import java.util.Date;
import java.util.Map;

/** A local stand-in for a provider's signing key, so ID tokens can be verified without the network. */
final class TestKeys {
    private final RSAKey key;

    TestKeys() {
        try {
            key = new RSAKeyGenerator(2048).keyID("test").generate();
        } catch (JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }

    NimbusJwtDecoder decoder() {
        try {
            return NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        } catch (JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }

    String idToken(String issuer, String audience, String subject, Map<String, Object> claims) {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(subject)
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(600)));
        claims.forEach(builder::claim);
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), builder.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
