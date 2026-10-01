package de.joinside.evmap_service.pricing;

import de.joinside.evmap_service.availability.Attribution;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The charge points of one station with their operator and price.
 *
 * @param cheapestEnergyPerKwh the lowest energy price among the charge points that have one, for the info
 *                             card's "from" price; {@code null} when none has
 * @param sources              the live sources whose prices are shown, which the client must credit
 */
public record StationPrices(UUID stationId, BigDecimal cheapestEnergyPerKwh, String currency,
                            List<PricedChargePoint> chargePoints, List<Attribution> sources) {

    /**
     * @param operatorName the charge point's operator, the station's where the source knows none per charge point
     * @param price        {@code null} when no price is known
     * @param priceSource  who stated the price: a live source's credited name, or the register's source token
     */
    public record PricedChargePoint(UUID id, String evseId, String operatorName,
                                    List<Connector> connectors,
                                    AdHocPrice price, String priceSource) {
    }

    public record Connector(String connectorType, BigDecimal powerKw, int quantity) {
    }
}
