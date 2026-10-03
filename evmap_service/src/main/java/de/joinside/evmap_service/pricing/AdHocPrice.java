package de.joinside.evmap_service.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The ad-hoc price of a charge point: gross (VAT included), in {@code currency}. Every amount is optional,
 * and an absent one means "not published", never "free" — that is {@code free}.
 *
 * @param energyPerKwh  price per kWh, when it is the same at every hour
 * @param energyWindows prices per kWh by time of day or weekday, when they differ ({@code energyPerKwh} is then
 *                      {@code null}; ADR 0022, L6p)
 * @param sessionFee    fee per charging session
 * @param timeFees      fees per minute of charging, each from a minute of the session on, in that order
 * @param free          charging costs nothing; never combined with an amount
 * @param furtherFees   the source lists fees that are deliberately not carried here (idle fees after charging, a
 *                      live tariff's lost time windows), so the client must not present the amounts as complete
 * @param observedAt    when the source last stated this price, {@code null} when unknown
 * @param paymentMeans  how this price is paid, as DATEX II names it ({@code qrCode}, {@code emv}, …), where a charge
 *                      point has several prices that differ by it; empty when the source says nothing
 */
public record AdHocPrice(String currency, BigDecimal energyPerKwh, List<EnergyWindow> energyWindows,
                         BigDecimal sessionFee, List<TimeFee> timeFees, boolean free, boolean furtherFees,
                         Instant observedAt, List<String> paymentMeans) {
    public AdHocPrice {
        energyWindows = energyWindows == null ? List.of() : List.copyOf(energyWindows);
        timeFees = timeFees == null ? List.of() : List.copyOf(timeFees);
        paymentMeans = paymentMeans == null ? List.of() : List.copyOf(paymentMeans);
    }

    /** A price without time windows or payment means — every live tariff, and every register price before L6p. */
    public AdHocPrice(String currency, BigDecimal energyPerKwh, BigDecimal sessionFee, List<TimeFee> timeFees,
                      boolean free, boolean furtherFees, Instant observedAt) {
        this(currency, energyPerKwh, List.of(), sessionFee, timeFees, free, furtherFees, observedAt, List.of());
    }

    /** The lowest energy price at any hour, for the station's "from" price; {@code null} when none is published. */
    public BigDecimal lowestEnergyPerKwh() {
        return Stream.concat(Stream.of(energyPerKwh), energyWindows.stream().map(EnergyWindow::perKwh))
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    /** Whether any part is limited in a way a client from before L6p could not show: hours, an end, a cap. */
    public boolean hasLimits() {
        return !energyWindows.isEmpty() || timeFees.stream()
                .anyMatch(fee -> fee.window() != null || fee.toMinute() != null || fee.cap() != null);
    }

    /**
     * @param fromMinute the minute of the session from which the fee applies, 0 for the whole session
     * @param toMinute   the minute from which it no longer applies, {@code null} for the rest of the session
     * @param perMinute  the gross fee per minute, or {@code null} when the source has one but its amount is not
     *                   certain — the client then says that a time-based fee applies, without a number
     * @param cap        the most this fee comes to in one session, {@code null} when uncapped
     * @param window     when the fee applies, {@code null} for always
     */
    public record TimeFee(int fromMinute, Integer toMinute, BigDecimal perMinute, BigDecimal cap, TimeWindow window) {
        public TimeFee(int fromMinute, BigDecimal perMinute) {
            this(fromMinute, null, perMinute, null, null);
        }
    }

    /** An energy price that applies only within {@code window}. */
    public record EnergyWindow(BigDecimal perKwh, TimeWindow window) {
    }

    /**
     * A recurring period of the week in local time, as the source writes it.
     *
     * @param from {@code HH:mm}
     * @param to   {@code HH:mm}, {@code 24:00} for midnight at its end; before {@code from} when it runs past midnight
     * @param days {@code monday} … {@code sunday}; empty for every day
     */
    public record TimeWindow(String from, String to, List<String> days) {
        public TimeWindow {
            days = days == null ? List.of() : List.copyOf(days);
        }
    }
}
