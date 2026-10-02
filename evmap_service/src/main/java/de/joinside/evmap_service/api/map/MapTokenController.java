package de.joinside.evmap_service.api.map;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;

/**
 * Hands the web map its MapKit JS token (ADR 0023). Public like the station reads, because the map has to work
 * signed out; what keeps the token from being useful elsewhere is its {@code origin} claim and its short lifetime.
 * Never cached and never logged.
 */
@RestController
@EnableConfigurationProperties(MapKitProperties.class)
class MapTokenController {
    private final MapKitTokenSigner signer;
    private final Clock clock;

    MapTokenController(MapKitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    MapTokenController(MapKitProperties properties, Clock clock) {
        // Built at startup, so a malformed key fails the deployment instead of every map view.
        this.signer = properties.enabled() ? new MapKitTokenSigner(properties) : null;
        this.clock = clock;
    }

    /** 404 while no key is configured, like a sign-in provider without credentials (ADR 0018). */
    @GetMapping("/api/v1/map/token")
    ResponseEntity<MapKitTokenSigner.MapToken> token() {
        if (signer == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(signer.sign(clock.instant()));
    }
}
