package de.joinside.evmap_service.availability;

import de.joinside.evmap_service.logging.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves live status onto stations, across whatever providers the component scan found.
 * <p>
 * Nothing here names a provider, exactly as {@code SyncJob} names no source adapter: a country gains
 * live coverage by a class appearing on the classpath, not by an edit to this file.
 * <p>
 * The resolution rule is the one thing this class exists to enforce: a charge point gets a live
 * status only when its stored EVSE-ID matches one a provider reported, compared in the normalized
 * form both sides produce through {@link de.joinside.evmap_service.sync.EvseIds}. Everything else is
 * {@link LiveAvailability#UNKNOWN}. See ADR 0015 for why the tempting alternatives — nearest
 * coordinate, matching operator name — were measured and rejected.
 */
@Service
@EnableConfigurationProperties(AvailabilityProperties.class)
@ConditionalOnProperty(name = "evmap.availability.enabled", havingValue = "true", matchIfMissing = true)
public class AvailabilityService {
    private static final Logger log = LoggerFactory.getLogger(AvailabilityService.class);

    private final List<AvailabilityProvider> providers;
    private final ChargePointDirectory directory;
    private final AvailabilityProperties properties;
    private final AvailabilityCache cache;

    AvailabilityService(List<AvailabilityProvider> providers,
                        ChargePointDirectory directory,
                        AvailabilityProperties properties) {
        this.providers = providers.stream().filter(AvailabilityProvider::enabled).toList();
        this.directory = directory;
        this.properties = properties;
        this.cache = new AvailabilityCache(properties.ttl(), properties.maxCacheEntries());
        if (this.providers.isEmpty())
            log.info("Live availability is enabled but no provider is registered — every station answers UNKNOWN");
        else
            log.info("Live availability providers: {}", this.providers.stream().map(AvailabilityProvider::source).toList());
    }

    /**
     * Live status of one station, with per-charge-point detail.
     *
     * @return empty when the station does not exist, so the caller can answer 404 rather than
     * inventing an unknown status for an id that means nothing.
     */
    public Optional<StationAvailability> forStation(UUID stationId) {
        Optional<ChargePointDirectory.StationLocation> location = directory.location(stationId);
        if (location.isEmpty()) return Optional.empty();

        List<ChargePointDirectory.KnownChargePoint> chargePoints = directory.forStation(stationId);
        if (chargePoints.isEmpty()) return Optional.of(StationAvailability.unknown(stationId, 0));

        boolean anyResolvable = chargePoints.stream().anyMatch(cp -> cp.evseIdNormalized() != null);
        if (!anyResolvable) {
            // Common — 69,7 % of declared Ladepunkte publish no EVSE-ID — and not worth an upstream
            // request: nothing a provider returned could match.
            log.debug("Station {} has {} charge point(s), none with an EVSE-ID", stationId, chargePoints.size());
            return Optional.of(StationAvailability.unknown(stationId, chargePoints.size()));
        }

        ChargePointDirectory.StationLocation at = location.get();
        GeoBounds bounds = GeoBounds.around(at.latitude(), at.longitude(), properties.stationRadius());
        Map<String, Reported> live = fetch(bounds, List.of(at.countryCode()), wanted(chargePoints));
        return Optional.of(summarize(stationId, chargePoints, live, true));
    }

    /**
     * Live status for every station in a viewport that has one.
     * <p>
     * Stations nothing is known about are left out entirely rather than returned as
     * {@link LiveAvailability#UNKNOWN}: the map already draws them from the station query, and an
     * unknown status changes nothing about the pin. Sending them would make the response scale with
     * the viewport instead of with the live coverage in it.
     */
    public List<StationAvailability> inBounds(GeoBounds bounds) {
        if (bounds.heightDegrees() > properties.maxViewportSpan() || bounds.widthDegrees() > properties.maxViewportSpan()) {
            log.debug("Viewport {}x{}° exceeds the {}° live-availability span — answering empty",
                    bounds.heightDegrees(), bounds.widthDegrees(), properties.maxViewportSpan());
            return List.of();
        }

        List<ChargePointDirectory.KnownChargePoint> chargePoints =
                directory.inBounds(bounds, properties.maxChargePoints());
        if (chargePoints.isEmpty()) return List.of();

        Map<String, Reported> live =
                fetch(bounds, directory.countriesInBounds(bounds), wanted(chargePoints));
        if (live.isEmpty()) return List.of();

        Map<UUID, List<ChargePointDirectory.KnownChargePoint>> byStation = new LinkedHashMap<>();
        for (ChargePointDirectory.KnownChargePoint chargePoint : chargePoints)
            byStation.computeIfAbsent(chargePoint.stationId(), key -> new ArrayList<>()).add(chargePoint);

        List<StationAvailability> answers = new ArrayList<>(byStation.size());
        byStation.forEach((stationId, stationChargePoints) -> {
            StationAvailability summary = summarize(stationId, stationChargePoints, live, false);
            if (summary.isKnown()) answers.add(summary.withoutDetail());
        });
        log.debug("Viewport live availability: {} of {} stations answered", answers.size(), byStation.size());
        return answers;
    }

    /**
     * Asks every provider that claims one of {@code countryCodes} and merges their answers.
     * <p>
     * A provider that throws is contained rather than propagated: one live source failing must cost
     * its own coverage and nothing else, the same containment ADR 0013 gives sync sources. Where two
     * providers report the same EVSE-ID, the newer observation wins (ADR 0015, "Mobilithek (L5)"):
     * MobiData BW re-publishes some of the operators' own Mobilithek feeds with a delay, and which
     * provider is consulted first says nothing about which answer is current.
     * <p>
     * Only the identifiers in {@code wanted} are kept. A provider may answer with far more than the
     * area holds — the French consolidation has no coordinates and answers with the whole country —
     * and copying all of that per request would make the cost of a map pan scale with the country.
     */
    private Map<String, Reported> fetch(GeoBounds bounds, List<String> countryCodes, Set<String> wanted) {
        Map<String, Reported> merged = new HashMap<>();
        for (AvailabilityProvider provider : providers) {
            if (countryCodes.stream().noneMatch(country -> covers(provider, country))) continue;
            try (var _ = LogContext.scope(LogContext.SOURCE, provider.source())) {
                List<ChargePointAvailability> reported =
                        cache.get(AvailabilityCache.key(provider.source(), bounds), () -> provider.fetch(bounds));
                for (ChargePointAvailability availability : reported)
                    if (availability.evseId() != null && wanted.contains(availability.evseId()))
                        merged.merge(availability.evseId(), new Reported(availability, creditFor(provider, availability)),
                                AvailabilityService::newer);
            } catch (RuntimeException e) {
                log.warn("Availability provider {} failed for bounds {} — that area answers UNKNOWN",
                        provider.source(), bounds, e);
            }
        }
        return merged;
    }

    /** One provider's answer for one charge point, kept with the credit its licence requires. */
    private record Reported(ChargePointAvailability availability, Attribution source) {
    }

    private static Attribution creditFor(AvailabilityProvider provider, ChargePointAvailability availability) {
        return availability.attribution() != null ? availability.attribution() : provider.attribution();
    }

    /** The more recent of two answers for one charge point, by the rule every provider shares. */
    private static Reported newer(Reported held, Reported candidate) {
        return Observations.newer(held, candidate, reported -> reported.availability().observedAt());
    }

    private static Set<String> wanted(List<ChargePointDirectory.KnownChargePoint> chargePoints) {
        Set<String> wanted = HashSet.newHashSet(chargePoints.size());
        for (ChargePointDirectory.KnownChargePoint chargePoint : chargePoints)
            if (chargePoint.evseIdNormalized() != null) wanted.add(chargePoint.evseIdNormalized());
        return wanted;
    }

    private static boolean covers(AvailabilityProvider provider, String countryCode) {
        return countryCode != null && provider.covers(countryCode.toUpperCase(Locale.ROOT));
    }

    private static StationAvailability summarize(UUID stationId,
                                                 List<ChargePointDirectory.KnownChargePoint> chargePoints,
                                                 Map<String, Reported> live,
                                                 boolean withDetail) {
        Tally tally = new Tally(withDetail, chargePoints.size());
        for (ChargePointDirectory.KnownChargePoint chargePoint : chargePoints) {
            String evseId = chargePoint.evseIdNormalized();
            tally.add(chargePoint, evseId == null ? null : live.get(evseId));
        }
        return tally.toStation(stationId);
    }

    /** The running summary of one station's charge points while they are resolved one by one. */
    private static final class Tally {
        private final boolean withDetail;
        private final List<StationAvailability.ChargePointStatus> detail;
        private final Set<Attribution> sources = new LinkedHashSet<>();
        private int available;
        private int occupied;
        private int outOfOrder;
        private int unknown;
        private Instant newest;

        Tally(boolean withDetail, int size) {
            this.withDetail = withDetail;
            this.detail = withDetail ? new ArrayList<>(size) : List.of();
        }

        /** @param resolved the provider's answer for this charge point, {@code null} when there is none */
        void add(ChargePointDirectory.KnownChargePoint chargePoint, Reported resolved) {
            ChargePointAvailability reported = resolved == null ? null : resolved.availability();
            Instant observedAt = reported == null ? null : reported.observedAt();
            String status = reported == null ? LiveAvailability.UNKNOWN : reported.status();

            count(status);
            if (resolved != null) sources.add(resolved.source());
            if (observedAt != null && (newest == null || observedAt.isAfter(newest))) newest = observedAt;
            if (withDetail)
                detail.add(new StationAvailability.ChargePointStatus(chargePoint.chargePointId(),
                        chargePoint.evseId(), status, observedAt,
                        resolved == null ? null : resolved.source().name()));
        }

        private void count(String status) {
            switch (status) {
                case LiveAvailability.AVAILABLE -> available++;
                case LiveAvailability.OCCUPIED -> occupied++;
                case LiveAvailability.OUT_OF_ORDER -> outOfOrder++;
                default -> unknown++;
            }
        }

        /**
         * One free charge point makes the station available: the user's question is whether they can
         * charge, not whether every stall is empty. Broken only wins when nothing else is known about the
         * station, so a site with one dead post and three working ones does not read as broken.
         */
        private static String summaryOf(int available, int occupied, int outOfOrder) {
            if (available > 0) return LiveAvailability.AVAILABLE;
            if (occupied > 0) return LiveAvailability.OCCUPIED;
            if (outOfOrder > 0) return LiveAvailability.OUT_OF_ORDER;
            return LiveAvailability.UNKNOWN;
        }

        StationAvailability toStation(UUID stationId) {
            return new StationAvailability(stationId, summaryOf(available, occupied, outOfOrder),
                    available, occupied, outOfOrder, unknown, newest, detail, List.copyOf(sources));
        }
    }
}
