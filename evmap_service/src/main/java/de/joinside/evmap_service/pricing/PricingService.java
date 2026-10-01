package de.joinside.evmap_service.pricing;

import de.joinside.evmap_service.availability.Attribution;
import de.joinside.evmap_service.availability.GeoBounds;
import de.joinside.evmap_service.logging.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The charge points of a station with their operator and ad-hoc price, across the register and every tariff
 * provider the component scan found.
 * <p>
 * A live tariff wins over the register's price: it is what the operator publishes now, while the register is
 * at best a day old. Both attach to a charge point only by its exact EVSE-ID (live) or because the register
 * stated them for that very charge point; there is no geographic or name-based matching (ADR 0015, ADR 0022).
 */
@Service
@EnableConfigurationProperties(PricingProperties.class)
@ConditionalOnProperty(name = "evmap.pricing.enabled", havingValue = "true", matchIfMissing = true)
public class PricingService {
    private static final Logger log = LoggerFactory.getLogger(PricingService.class);

    private final List<PriceProvider> providers;
    private final ChargePointInventory inventory;
    private final PricingProperties properties;
    private final PriceCache cache;

    PricingService(List<PriceProvider> providers, ChargePointInventory inventory, PricingProperties properties) {
        this.providers = providers.stream().filter(PriceProvider::enabled).toList();
        this.inventory = inventory;
        this.properties = properties;
        this.cache = new PriceCache(properties.ttl(), properties.maxCacheEntries());
        log.info("Price providers: {}", this.providers.stream().map(PriceProvider::source).toList());
    }

    /** @return empty when the station does not exist, so the caller answers 404 */
    public Optional<StationPrices> forStation(UUID stationId) {
        Optional<ChargePointInventory.StationLocation> location = inventory.location(stationId);
        if (location.isEmpty()) return Optional.empty();
        ChargePointInventory.StationLocation at = location.get();

        List<ChargePointInventory.KnownChargePoint> chargePoints = inventory.forStation(stationId);
        Map<String, Reported> live = live(at, chargePoints);

        List<StationPrices.PricedChargePoint> priced = chargePoints.stream()
                .map(chargePoint -> priced(chargePoint, at, live))
                .toList();
        Set<Attribution> sources = new LinkedHashSet<>();
        for (ChargePointInventory.KnownChargePoint chargePoint : chargePoints) {
            Reported reported = reportedFor(chargePoint, live);
            if (reported != null) sources.add(reported.source());
        }
        Optional<AdHocPrice> cheapest = priced.stream()
                .map(StationPrices.PricedChargePoint::price)
                .filter(price -> price != null && price.energyPerKwh() != null)
                .min(Comparator.comparing(AdHocPrice::energyPerKwh));
        log.debug("Station {}: {} of {} charge point(s) priced, {} live", stationId,
                priced.stream().filter(p -> p.price() != null).count(), priced.size(), live.size());
        return Optional.of(new StationPrices(stationId, cheapest.map(AdHocPrice::energyPerKwh).orElse(null),
                cheapest.map(AdHocPrice::currency).orElse(null), priced, List.copyOf(sources)));
    }

    /** The live tariff for this charge point, if a provider reported one for its EVSE-ID. */
    private static Reported reportedFor(ChargePointInventory.KnownChargePoint chargePoint, Map<String, Reported> live) {
        return chargePoint.evseIdNormalized() == null ? null : live.get(chargePoint.evseIdNormalized());
    }

    /** A live tariff wins over the register's price; the operator falls back to the station's. */
    private static StationPrices.PricedChargePoint priced(ChargePointInventory.KnownChargePoint chargePoint,
                                                          ChargePointInventory.StationLocation at,
                                                          Map<String, Reported> live) {
        Reported reported = reportedFor(chargePoint, live);
        AdHocPrice price;
        String priceSource;
        if (reported != null) {
            price = reported.price();
            priceSource = reported.source().name();
        } else {
            price = chargePoint.price();
            priceSource = price == null ? null : chargePoint.source();
        }
        String operator = chargePoint.operatorName() != null ? chargePoint.operatorName() : at.operatorName();
        return new StationPrices.PricedChargePoint(chargePoint.id(), chargePoint.evseId(), operator,
                chargePoint.connectors(), price, priceSource);
    }

    private Map<String, Reported> live(ChargePointInventory.StationLocation at,
                                       List<ChargePointInventory.KnownChargePoint> chargePoints) {
        Set<String> wanted = chargePoints.stream()
                .map(ChargePointInventory.KnownChargePoint::evseIdNormalized)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (wanted.isEmpty() || at.countryCode() == null) return Map.of();

        String country = at.countryCode().toUpperCase(Locale.ROOT);
        GeoBounds bounds = GeoBounds.around(at.latitude(), at.longitude(), properties.stationRadius());
        Map<String, Reported> merged = new HashMap<>();
        for (PriceProvider provider : providers)
            if (provider.covers(country)) merge(provider, bounds, wanted, merged);
        return merged;
    }

    /**
     * Adds what one provider reports for the wanted EVSE-IDs. The first provider to report an id wins. A provider
     * that throws costs its own prices and nothing else (ADR 0013's containment).
     */
    private void merge(PriceProvider provider, GeoBounds bounds, Set<String> wanted, Map<String, Reported> into) {
        try (var _ = LogContext.scope(LogContext.SOURCE, provider.source())) {
            cache.get(PriceCache.key(provider.source(), bounds), () -> provider.fetch(bounds)).stream()
                    .filter(reported -> reported.evseId() != null && wanted.contains(reported.evseId()))
                    .forEach(reported -> into.putIfAbsent(reported.evseId(),
                            new Reported(reported.price(), provider.attribution())));
        } catch (RuntimeException e) {
            log.warn("Price provider {} failed for bounds {} — those prices stay unknown", provider.source(), bounds, e);
        }
    }

    private record Reported(AdHocPrice price, Attribution source) {
    }
}
