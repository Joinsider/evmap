package de.joinside.evmap_service.api.map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * The key MapKit JS tokens are signed with (ADR 0023). Blank key material switches the web map off: the token
 * endpoint answers 404 and the web client says the map is not available, so a local stack and CI run without one.
 *
 * @param teamId     the Apple developer team id, the token's {@code iss}
 * @param keyId      the Maps key's id, the token's {@code kid}
 * @param privateKey the {@code .p8} key's PEM text (PKCS#8), newlines as-is or as {@code \n}
 * @param origin     the domain the token is bound to, without scheme ({@code evmap.joinside.de}); MapKit JS
 *                   refuses the token on any other page
 * @param lifetime   how long a token is valid; the web client asks for a new one when MapKit JS needs it
 */
@ConfigurationProperties("evmap.mapkit")
record MapKitProperties(@DefaultValue("") String teamId, @DefaultValue("") String keyId,
                        @DefaultValue("") String privateKey, @DefaultValue("evmap.joinside.de") String origin,
                        @DefaultValue("30m") Duration lifetime) {

    boolean enabled() {
        return !teamId.isBlank() && !keyId.isBlank() && !privateKey.isBlank() && !origin.isBlank();
    }
}
