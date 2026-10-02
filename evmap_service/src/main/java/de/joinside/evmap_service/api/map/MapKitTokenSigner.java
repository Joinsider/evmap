package de.joinside.evmap_service.api.map;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * Signs the token MapKit JS authorizes itself with: an ES256 JWT with the Maps key's id as {@code kid}, the team
 * as {@code iss}, {@code scope: mapkit_js} and the web domain as {@code origin} (Apple, "Creating and using tokens
 * with Maps Server API"). Short-lived on purpose: the token is public by nature — every visitor's browser holds
 * one — so what limits a copied token is its domain binding and its expiry, not secrecy.
 */
final class MapKitTokenSigner {
    static final String SCOPE = "mapkit_js";

    private final MapKitProperties properties;
    private final ECPrivateKey key;

    MapKitTokenSigner(MapKitProperties properties) {
        this.properties = properties;
        try {
            this.key = privateKey(properties.privateKey());
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException exception) {
            // Never include the key material; the type of failure is enough to find a bad MAPKIT_PRIVATE_KEY.
            throw new IllegalStateException("MAPKIT_PRIVATE_KEY is not a PKCS#8 EC key (" + exception.getClass().getSimpleName() + ")");
        }
    }

    MapToken sign(Instant now) {
        Instant expiresAt = now.plus(properties.lifetime());
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.teamId())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .claim("scope", SCOPE)
                .claim("origin", properties.origin())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(properties.keyId())
                .type(JOSEObjectType.JWT).build(), claims);
        try {
            jwt.sign(new ECDSASigner(key));
        } catch (JOSEException exception) {
            throw new IllegalStateException("Could not sign the MapKit JS token (" + exception.getClass().getSimpleName() + ")");
        }
        return new MapToken(jwt.serialize(), expiresAt);
    }

    private static ECPrivateKey privateKey(String pem) throws GeneralSecurityException {
        String base64 = pem.replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }

    /** @param expiresAt when MapKit JS will need the next one */
    record MapToken(String token, Instant expiresAt) {
    }
}
