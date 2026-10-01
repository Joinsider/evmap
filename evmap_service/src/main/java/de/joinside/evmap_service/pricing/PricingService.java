package de.joinside.evmap_service.pricing;

import de.joinside.evmap_service.availability.Attribution;
import de.joinside.evmap_service.availability.GeoBounds;
import de.joinside.evmap_service.logging.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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

        List<StationPrices.PricedChargePoint> priced = new ArrayList<>(chargePoints.size());
        Set<Attribution> sources = new LinkedHashSet<>();
        BigDecimal cheapest = null;
        String currency = null;
        for (ChargePointInventory.KnownChargePoint chargePoint : chargePoints) {
            Reported reported = chargePoint.evseIdNormalized() == null ? null : live.get(chargePoint.evseIdNormalized());
            AdHocPrice price = reported != null ? reported.price() : chargePoint.price();
            String priceSource = reported != null ? reported.source().name() : price == null ? null : chargePoint.source();
            if (reported != null) sources.add(reported.source());

            BigDecimal energy = price == null ? null : price.energyPerKwh();
            if (energy != null && (cheapest == null || energy.compareTo(cheapest) < 0)) {
                cheapest = energy;
                currency = price.currency();
            }
            String operator = chargePoint.operatorName() != null ? chargePoint.operatorName() : at.operatorName();
            priced.add(new StationPrices.PricedChargePoint(chargePoint.id(), chargePoint.evseId(), operator,
                    chargePoint.connectors(), price, priceSource));
        }
        log.debug("Station {}: {} of {} charge point(s) priced, {} live", stationId,
                priced.stream().filter(p -> p.price() != null).count(), priced.size(), live.size());
        return Optional.of(new StationPrices(stationId, cheapest, currency, priced, List.copyOf(sources)));
    }

    private Map<String, Reported> live(ChargePointInventory.StationLocation at,
                                       List<ChargePointInventory.KnownChargePoint> chargePoints) {
        Set<String> wanted = new LinkedHashSet<>();
        for (ChargePointInventory.KnownChargePoint chargePoint : chargePoints)
            if (chargePoint.evseIdNormalized() != null) wanted.add(chargePoint.evseIdNormalized());
        if (wanted.isEmpty() || at.countryCode() == null) return Map.of();

        String country = at.countryCode().toUpperCase(Locale.ROOT);
        GeoBounds bounds = GeoBounds.around(at.latitude(), at.longitude(), properties.stationRadius());
        Map<String, Reported> merged = new HashMap<>();
        for (PriceProvider provider : providers) {
            if (!provider.covers(country)) continue;
            try (var _ = LogContext.scope(LogContext.SOURCE, provider.source())) {
                for (ChargePointPrice reported : cache.get(PriceCache.key(provider.source(), bounds),
                        () -> provider.fetch(bounds)))
                    if (reported.evseId() != null && wanted.contains(reported.evseId()))
                        merged.putIfAbsent(reported.evseId(), new Reported(reported.price(), provider.attribution()));
            } catch (RuntimeException e) {
                // One tariff source failing must cost its own prices and nothing else (ADR 0013's containment).
                log.warn("Price provider {} failed for bounds {} — those prices stay unknown", provider.source(), bounds, e);
            }
        }
        return merged;
    }

    private record Reported(AdHocPrice price, Attribution source) {
    }
}
