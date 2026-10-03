package de.joinside.evmap_service.api.station;

import de.joinside.evmap_service.pricing.AdHocPrice;
import de.joinside.evmap_service.pricing.PricingService;
import de.joinside.evmap_service.pricing.StationPrices;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The charge points of one station, with their operator and ad-hoc price (ADR 0022).
 * <p>
 * Served apart from the station itself for the reason live availability is: a tariff feed that is down must
 * leave prices unknown, not fail the station screen. Public like every {@code GET /api/v1/stations/**}: a
 * price published under AFIR is public information.
 */
@RestController
@RequestMapping("/api/v1/stations")
@ConditionalOnBean(PricingService.class)
class ChargePointController {
    private final PricingService pricing;

    ChargePointController(PricingService pricing) {
        this.pricing = pricing;
    }

    @GetMapping("/{id}/charge-points")
    ChargePointsResponse forStation(@PathVariable UUID id) {
        return pricing.forStation(id)
                .map(ChargePointController::toResponse)
                .orElseThrow(() -> new StationController.StationNotFoundException(id));
    }

    /**
     * @param cheapestEnergyPerKwh the lowest gross energy price among the charge points, for the info card
     * @param sources              live sources whose prices appear here, to be credited next to them
     */
    record ChargePointsResponse(UUID stationId, BigDecimal cheapestEnergyPerKwh, String currency,
                                List<ChargePointResponse> chargePoints, List<SourceResponse> sources) {
    }

    /**
     * @param operatorName the charge point's operator; the station's where the source knows none per charge point
     * @param price        the one price in the shape clients before L6p read: {@code null} when there are several or
     *                     it has time windows, a fee's end or cap, so such a client shows no price rather than a wrong one
     * @param prices       every price, usually one; several where they differ by payment means (ADR 0022, L6p)
     */
    record ChargePointResponse(UUID id, String evseId, String operatorName, List<StationController.Connector> connectors,
                               PriceResponse price, List<PriceResponse> prices) {
    }

    /**
     * Gross amounts; an absent amount means "not published", never "free".
     *
     * @param energyWindows energy prices by time of day, where they differ ({@code energyPerKwh} is then absent)
     * @param furtherFees   the source lists fees that are not shown, so the amounts are not complete
     * @param paymentMeans  DATEX II payment means this price applies to, for labelling several prices apart
     * @param source        who stated the price: a live source's credited name, a publisher, or a register's token
     */
    record PriceResponse(String currency, BigDecimal energyPerKwh, List<EnergyWindowResponse> energyWindows,
                         BigDecimal sessionFee, List<TimeFeeResponse> timeFees, boolean free, boolean furtherFees,
                         Instant observedAt, List<String> paymentMeans, String source) {
    }

    /**
     * @param toMinute  the minute from which the fee no longer applies, {@code null} for the rest of the session
     * @param perMinute {@code null} when a time-based fee applies but its amount is not certain
     * @param cap       the most the fee comes to in one session, {@code null} when uncapped
     * @param window    when the fee applies, {@code null} for always
     */
    record TimeFeeResponse(int fromMinute, Integer toMinute, BigDecimal perMinute, BigDecimal cap, WindowResponse window) {
    }

    record EnergyWindowResponse(BigDecimal perKwh, WindowResponse window) {
    }

    /** Local time, {@code HH:mm}; {@code to} may be {@code 24:00} or before {@code from}; no days means every day. */
    record WindowResponse(String from, String to, List<String> days) {
    }

    record SourceResponse(String name, String licence, String url) {
    }

    private static ChargePointsResponse toResponse(StationPrices prices) {
        return new ChargePointsResponse(prices.stationId(), prices.cheapestEnergyPerKwh(), prices.currency(),
                prices.chargePoints().stream()
                        .map(chargePoint -> {
                            List<PriceResponse> all = chargePoint.prices().stream()
                                    .map(price -> price(price, chargePoint.priceSource()))
                                    .toList();
                            AdHocPrice single = chargePoint.price();
                            PriceResponse legacy = single == null || single.hasLimits() ? null : all.getFirst();
                            return new ChargePointResponse(chargePoint.id(), chargePoint.evseId(),
                                    chargePoint.operatorName(),
                                    chargePoint.connectors().stream()
                                            .map(c -> new StationController.Connector(c.connectorType(), c.powerKw(), c.quantity()))
                                            .toList(),
                                    legacy, all);
                        })
                        .toList(),
                prices.sources().stream()
                        .map(source -> new SourceResponse(source.name(), source.licence(), source.url()))
                        .toList());
    }

    private static PriceResponse price(AdHocPrice price, String source) {
        return new PriceResponse(price.currency(), price.energyPerKwh(),
                price.energyWindows().stream()
                        .map(window -> new EnergyWindowResponse(window.perKwh(), window(window.window())))
                        .toList(),
                price.sessionFee(),
                price.timeFees().stream()
                        .map(fee -> new TimeFeeResponse(fee.fromMinute(), fee.toMinute(), fee.perMinute(), fee.cap(),
                                window(fee.window())))
                        .toList(),
                price.free(), price.furtherFees(), price.observedAt(), price.paymentMeans(), source);
    }

    private static WindowResponse window(AdHocPrice.TimeWindow window) {
        return window == null ? null : new WindowResponse(window.from(), window.to(), window.days());
    }
}
