package de.joinside.evmap_service.api.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * Apple's "client secret": a JWT the backend signs with the Sign in with Apple key (ES256), instead
 * of a fixed string. Made fresh for each exchange with a short lifetime, so there is nothing to cache
 * or to rotate apart from the key itself.
 */
final class AppleClientSecret {
    static final String AUDIENCE = "https://appleid.apple.com";
    private static final Duration LIFETIME = Duration.ofMinutes(5);

    private AppleClientSecret() {
    }

    static String create(AuthProperties.AppleWeb apple, Instant now) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(apple.teamId()).subject(apple.servicesId()).audience(AUDIENCE)
                    .issueTime(Date.from(now)).expirationTime(Date.from(now.plus(LIFETIME)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(apple.keyId()).build(), claims);
            jwt.sign(new ECDSASigner(privateKey(apple.privateKey())));
            return jwt.serialize();
        } catch (JOSEException | GeneralSecurityException | IllegalArgumentException exception) {
            // Never include the key material; the type of failure is enough to find a bad APPLE_PRIVATE_KEY.
            throw new IllegalStateException("Could not sign the Apple client secret (" + exception.getClass().getSimpleName() + ")");
        }
    }

    static ECPrivateKey privateKey(String pem) throws GeneralSecurityException {
        String base64 = pem.replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }
}
