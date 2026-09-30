package de.joinside.evmap_service.api.favorite;

import de.joinside.evmap_service.api.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The caller's favorite stations (ADR 0021). Signed out, the clients keep them on the device; these
 * endpoints exist for the account's copy. The list carries the same station fields as the map, so a
 * client can show and pin a favorite without a second request per station.
 */
@RestController
class FavoriteController {
    private final FavoriteService favorites;

    FavoriteController(FavoriteService favorites) {
        this.favorites = favorites;
    }

    @GetMapping("/api/v1/me/favorites")
    List<FavoriteStation> list(@AuthenticationPrincipal CurrentUser user) {
        return favorites.list(user.accountId());
    }

    @PutMapping("/api/v1/me/favorites/{stationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void add(@PathVariable UUID stationId, @AuthenticationPrincipal CurrentUser user) {
        favorites.add(user.accountId(), stationId);
    }

    @DeleteMapping("/api/v1/me/favorites/{stationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable UUID stationId, @AuthenticationPrincipal CurrentUser user) {
        favorites.remove(user.accountId(), stationId);
    }

    /**
     * The sign-in merge: adds the device's favorites to the account's and answers with the union, so the
     * client replaces its copy with the response. Ids the API does not know any more are skipped, and
     * so are those beyond the account's limit.
     */
    @PostMapping("/api/v1/me/favorites/merge")
    List<FavoriteStation> merge(@RequestBody MergeRequest request, @AuthenticationPrincipal CurrentUser user) {
        return favorites.merge(user.accountId(), request.stationIds());
    }

    record MergeRequest(List<UUID> stationIds) {
    }

    /** The station fields of {@code StationSummary} (same names, so clients decode both alike) plus when it was favorited. */
    record FavoriteStation(UUID id, String displayName, String street, String city, String postalCode, String countryCode,
                           String operatorName, double latitude, double longitude, String availabilityStatus,
                           BigDecimal maxPowerKw, Instant favoritedAt) {
    }
}
