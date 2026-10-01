package de.joinside.evmap_service.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The ad-hoc price of a charge point: gross (VAT included), in {@code currency}. Every amount is optional,
 * and an absent one means "not published", never "free" — that is {@code free}.
 *
 * @param energyPerKwh price per kWh
 * @param sessionFee   fee per charging session
 * @param timeFees     fees per minute of charging, each from a minute of the session on, in that order
 * @param free         charging costs nothing; never combined with an amount
 * @param furtherFees  the source lists fees that are deliberately not carried here (idle fees, time windows),
 *                     so the client must not present the amounts as complete
 * @param observedAt   when the source last stated this price, {@code null} when unknown
 */
public record AdHocPrice(String currency, BigDecimal energyPerKwh, BigDecimal sessionFee, List<TimeFee> timeFees,
                         boolean free, boolean furtherFees, Instant observedAt) {

    public AdHocPrice {
        timeFees = timeFees == null ? List.of() : List.copyOf(timeFees);
    }

    /**
     * @param fromMinute the minute of the session from which the fee applies, 0 for the whole session
     * @param perMinute  the gross fee per minute, or {@code null} when the source has one but its amount is not
     *                   certain — the client then says that a time-based fee applies, without a number
     */
    public record TimeFee(int fromMinute, BigDecimal perMinute) {
    }
}
