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

    /** @param operatorName the charge point's operator; the station's where the source knows none per charge point */
    record ChargePointResponse(UUID id, String evseId, String operatorName, List<StationController.Connector> connectors,
                               PriceResponse price) {
    }

    /**
     * Gross amounts; an absent amount means "not published", never "free".
     *
     * @param furtherFees the source lists fees that are not shown, so the amounts are not complete
     * @param source      who stated the price: a live source's credited name or the register's source token
     */
    record PriceResponse(String currency, BigDecimal energyPerKwh, BigDecimal sessionFee, List<TimeFeeResponse> timeFees,
                         boolean free, boolean furtherFees, Instant observedAt, String source) {
    }

    /** @param perMinute {@code null} when a time-based fee applies but its amount is not certain */
    record TimeFeeResponse(int fromMinute, BigDecimal perMinute) {
    }

    record SourceResponse(String name, String licence, String url) {
    }

    private static ChargePointsResponse toResponse(StationPrices prices) {
        return new ChargePointsResponse(prices.stationId(), prices.cheapestEnergyPerKwh(), prices.currency(),
                prices.chargePoints().stream()
                        .map(chargePoint -> new ChargePointResponse(chargePoint.id(), chargePoint.evseId(),
                                chargePoint.operatorName(),
                                chargePoint.connectors().stream()
                                        .map(c -> new StationController.Connector(c.connectorType(), c.powerKw(), c.quantity()))
                                        .toList(),
                                price(chargePoint.price(), chargePoint.priceSource())))
                        .toList(),
                prices.sources().stream()
                        .map(source -> new SourceResponse(source.name(), source.licence(), source.url()))
                        .toList());
    }

    private static PriceResponse price(AdHocPrice price, String source) {
        if (price == null) return null;
        return new PriceResponse(price.currency(), price.energyPerKwh(), price.sessionFee(),
                price.timeFees().stream().map(fee -> new TimeFeeResponse(fee.fromMinute(), fee.perMinute())).toList(),
                price.free(), price.furtherFees(), price.observedAt(), source);
    }
}
