package de.joinside.evmap_service.pricing.mobidata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.joinside.evmap_service.pricing.AdHocPrice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads OCPDB's OCPI tariffs into gross ad-hoc prices — only where the gross amount is established.
 * <p>
 * Two defects of the feed shape this class (ADR 0022, checked against OCPDB 2.16.2 on 2026-10-01):
 * <ul>
 *   <li><strong>The VAT basis is lost.</strong> DATEX II says per price whether tax is included; OCPDB keeps the
 *       rate and drops the flag (binary-butterfly/ocpdb#278). OCPI defines prices as net, but a third of the
 *       tariffs are evidently gross. So the basis is taken, in order, from an explicit {@code tax_included} once
 *       OCPDB delivers one; from arithmetic evidence (a non-round net price that lands on whole cents with its
 *       rate); from a hand-kept table of operators known to publish net or gross. Otherwise: no price.</li>
 *   <li><strong>Time prices are per minute.</strong> OCPI's {@code TIME} is per hour, but OCPDB maps DATEX
 *       {@code pricePerMinute} onto it unconverted. Because that may be fixed upstream at any time, the unit
 *       is detected per source from the data on every refresh ({@link #detectTimeUnits}).</li>
 * </ul>
 * Anything this class does not fully understand — restrictions by time of day, power or weekday, two different
 * energy prices, an energy price that changes after some minutes — yields no price rather than a guess.
 */
final class OcpiTariffs {
    /** Plausible gross energy prices; outside is a different unit or a typo. */
    private static final BigDecimal MIN_ENERGY = new BigDecimal("0.05");
    private static final BigDecimal MAX_ENERGY = new BigDecimal("1.50");
    private static final BigDecimal MAX_SESSION = new BigDecimal("20");
    /** Real per-minute fees lie in this band; real per-hour fees between 0,60 and 60 €/h. */
    static final BigDecimal MIN_PER_MINUTE = new BigDecimal("0.005");
    static final BigDecimal MAX_PER_MINUTE = BigDecimal.ONE;
    static final BigDecimal MAX_PER_HOUR = new BigDecimal("60");
    private static final BigDecimal SIXTY = new BigDecimal("60");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private OcpiTariffs() {
    }

    // --- response shapes ------------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TariffPage(@JsonProperty("items") List<Tariff> items,
                      @JsonProperty("total_count") Integer totalCount,
                      @JsonProperty("next_offset") Integer nextOffset) {
    }

    /**
     * @param originalId  the id connectors reference in {@code tariff_ids}
     * @param source      OCPDB's feed token, e.g. {@code datex2_ecomovement}; the time unit is detected per feed
     * @param taxIncluded not delivered by OCPDB yet (#278); read so the day it arrives, it wins
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Tariff(@JsonProperty("original_id") String originalId,
                  @JsonProperty("source") String source,
                  @JsonProperty("currency") String currency,
                  @JsonProperty("last_updated") Instant lastUpdated,
                  @JsonProperty("tax_included") Boolean taxIncluded,
                  @JsonProperty("elements") List<Element> elements) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Element(@JsonProperty("price_components") List<PriceComponent> priceComponents,
                   @JsonProperty("restrictions") Map<String, Object> restrictions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PriceComponent(@JsonProperty("type") String type,
                          @JsonProperty("price") BigDecimal price,
                          @JsonProperty("taxes") List<Tax> taxes,
                          @JsonProperty("tax_included") Boolean taxIncluded) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Tax(@JsonProperty("name") String name, @JsonProperty("percentage") String percentage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LocationPage(@JsonProperty("items") List<Location> items,
                        @JsonProperty("total_count") Integer totalCount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Location(@JsonProperty("operator") Operator operator,
                    @JsonProperty("charging_pool") List<ChargeStation> chargingPool) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Operator(@JsonProperty("name") String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChargeStation(@JsonProperty("evses") List<Evse> evses) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Evse(@JsonProperty("evse_id") String evseId,
                @JsonProperty("original_uid") String originalUid,
                @JsonProperty("connectors") List<Connector> connectors) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Connector(@JsonProperty("tariff_ids") List<String> tariffIds) {
    }

    // --- time unit ------------------------------------------------------------------------------------

    enum TimeUnit { PER_MINUTE, PER_HOUR, UNKNOWN }

    /**
     * The unit of {@code TIME} prices per feed, decided by the median of its non-zero time prices: in the
     * per-minute band it is per minute, in the per-hour band per hour, anything else unknown. No feed mixes
     * the two, and the bands do not overlap, so one bad tariff cannot flip a feed.
     */
    static Map<String, TimeUnit> detectTimeUnits(Collection<Tariff> tariffs) {
        Map<String, List<BigDecimal>> prices = new HashMap<>();
        for (Tariff tariff : tariffs) {
            if (tariff.source() == null || tariff.elements() == null) continue;
            for (Element element : tariff.elements()) {
                if (element.priceComponents() == null) continue;
                for (PriceComponent component : element.priceComponents())
                    if ("TIME".equals(component.type()) && component.price() != null && component.price().signum() > 0)
                        prices.computeIfAbsent(tariff.source(), key -> new ArrayList<>()).add(component.price());
            }
        }
        Map<String, TimeUnit> units = new HashMap<>();
        prices.forEach((source, values) -> {
            values.sort(null);
            BigDecimal median = values.get(values.size() / 2);
            units.put(source, unitOf(median));
        });
        return units;
    }

    static TimeUnit unitOf(BigDecimal median) {
        if (median.compareTo(MIN_PER_MINUTE) >= 0 && median.compareTo(MAX_PER_MINUTE) <= 0) return TimeUnit.PER_MINUTE;
        if (median.compareTo(MAX_PER_MINUTE) > 0 && median.compareTo(MAX_PER_HOUR) <= 0) return TimeUnit.PER_HOUR;
        return TimeUnit.UNKNOWN;
    }

    // --- VAT basis ------------------------------------------------------------------------------------

    /** Operators whose publishing basis was checked by hand against their own price pages (phase 5r). */
    record VatBasisTable(Set<String> netOperators, Set<String> grossOperators) {

        static VatBasisTable of(Collection<String> net, Collection<String> gross) {
            return new VatBasisTable(normalized(net), normalized(gross));
        }

        private static Set<String> normalized(Collection<String> names) {
            Set<String> set = new LinkedHashSet<>();
            if (names != null) for (String name : names) if (name != null && !name.isBlank()) set.add(key(name));
            return Set.copyOf(set);
        }

        static String key(String operatorName) {
            return operatorName.trim().toLowerCase(Locale.ROOT);
        }

        Boolean netFor(String operatorName) {
            if (operatorName == null) return null;
            String key = key(operatorName);
            if (netOperators.contains(key)) return true;
            if (grossOperators.contains(key)) return false;
            return null;
        }
    }

    /**
     * The gross price when {@code net} is demonstrably net at {@code rate}, else {@code null}: more than two
     * decimals, and net × (1 + rate) on whole cents within the rounding of the decimals given. Mirrors the rule
     * the IRVE recognizer applies to French prices (ADR 0022).
     */
    static BigDecimal provenGross(BigDecimal net, BigDecimal rate) {
        int decimals = net.stripTrailingZeros().scale();
        if (decimals <= 2) return null;
        BigDecimal gross = net.multiply(BigDecimal.ONE.add(rate));
        BigDecimal cents = gross.setScale(2, RoundingMode.HALF_UP);
        BigDecimal tolerance = new BigDecimal("0.6").movePointLeft(decimals);
        return gross.subtract(cents).abs().compareTo(tolerance) <= 0 ? cents : null;
    }

    // --- reading one tariff ---------------------------------------------------------------------------

    /**
     * @param operatorName the operator of the location the tariff is attached to, for the basis table
     * @param unit         the detected time unit of the tariff's feed
     * @return the gross price, or {@code null} when anything about it is uncertain
     */
    static AdHocPrice read(Tariff tariff, String operatorName, TimeUnit unit, VatBasisTable table) {
        if (tariff == null || tariff.elements() == null || !"EUR".equals(tariff.currency())) return null;

        Set<BigDecimal> energies = new LinkedHashSet<>();
        Set<BigDecimal> flats = new LinkedHashSet<>();
        Map<Integer, BigDecimal> timeFees = new TreeMap<>();
        Set<BigDecimal> rates = new LinkedHashSet<>();
        Set<Boolean> included = new LinkedHashSet<>();
        boolean furtherFees = false;

        for (Element element : tariff.elements()) {
            if (element.priceComponents() == null) continue;
            Integer fromMinute = fromMinute(element.restrictions());
            if (fromMinute == null) return null;
            for (PriceComponent component : element.priceComponents()) {
                if (component.price() == null || component.price().signum() < 0) return null;
                BigDecimal rate = rate(component.taxes());
                if (rate == null) return null;
                if (rate.signum() > 0) rates.add(rate);
                if (component.taxIncluded() != null) included.add(component.taxIncluded());
                switch (component.type() == null ? "" : component.type()) {
                    case "ENERGY" -> {
                        // An energy price that changes after some minutes is a second price; not shown.
                        if (fromMinute != 0) return null;
                        energies.add(component.price().stripTrailingZeros());
                    }
                    case "FLAT" -> {
                        if (fromMinute != 0) return null;
                        if (component.price().signum() > 0) flats.add(component.price().stripTrailingZeros());
                    }
                    case "TIME" -> {
                        if (component.price().signum() == 0) continue;
                        BigDecimal previous = timeFees.putIfAbsent(fromMinute, component.price().stripTrailingZeros());
                        if (previous != null && previous.compareTo(component.price()) != 0) return null;
                    }
                    case "PARKING_TIME" -> furtherFees = component.price().signum() > 0 || furtherFees;
                    default -> {
                        return null;
                    }
                }
            }
        }
        if (energies.size() != 1 || flats.size() > 1 || rates.size() > 1 || included.size() > 1) return null;
        if (tariff.taxIncluded() != null) included = Set.of(tariff.taxIncluded());

        BigDecimal energy = energies.iterator().next();
        // Nothing to pay, whatever the VAT basis: free.
        if (energy.signum() == 0 && flats.isEmpty() && timeFees.isEmpty())
            return new AdHocPrice("EUR", null, null, List.of(), true, furtherFees, tariff.lastUpdated());

        BigDecimal rate = rates.isEmpty() ? null : rates.iterator().next();
        Basis basis = basis(energy, rate, included, table.netFor(operatorName));
        if (basis == null) return null;

        BigDecimal grossEnergy = basis.energy;
        if (grossEnergy.compareTo(MIN_ENERGY) < 0 || grossEnergy.compareTo(MAX_ENERGY) > 0) return null;

        BigDecimal session = flats.isEmpty() ? null : basis.gross(flats.iterator().next());
        if (session != null && session.compareTo(MAX_SESSION) > 0) return null;

        List<AdHocPrice.TimeFee> fees = new ArrayList<>();
        timeFees.forEach((minute, price) -> fees.add(new AdHocPrice.TimeFee(minute, perMinute(price, unit, basis))));
        return new AdHocPrice("EUR", grossEnergy, session, fees, false, furtherFees, tariff.lastUpdated());
    }

    /** How net amounts become gross, once the basis is known. */
    private record Basis(BigDecimal energy, BigDecimal factor) {
        BigDecimal gross(BigDecimal amount) {
            return amount.multiply(factor).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        }
    }

    private static Basis basis(BigDecimal energy, BigDecimal rate, Set<Boolean> included, Boolean tableSaysNet) {
        if (!included.isEmpty()) {
            if (included.iterator().next()) return new Basis(energy, BigDecimal.ONE);
            return rate == null ? null : net(energy, rate);
        }
        if (rate != null) {
            BigDecimal proven = provenGross(energy, rate);
            if (proven != null) return new Basis(proven, BigDecimal.ONE.add(rate));
        }
        if (Boolean.TRUE.equals(tableSaysNet)) return rate == null ? null : net(energy, rate);
        if (Boolean.FALSE.equals(tableSaysNet)) return new Basis(energy, BigDecimal.ONE);
        return null;
    }

    /** A price known to be net: gross rounded to the cent, because no one charges a fraction of one. */
    private static Basis net(BigDecimal energy, BigDecimal rate) {
        BigDecimal factor = BigDecimal.ONE.add(rate);
        return new Basis(energy.multiply(factor).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros(), factor);
    }

    private static BigDecimal perMinute(BigDecimal price, TimeUnit unit, Basis basis) {
        BigDecimal perMinute = switch (unit) {
            case PER_MINUTE -> price;
            case PER_HOUR -> price.divide(SIXTY, 6, RoundingMode.HALF_UP);
            case UNKNOWN -> null;
        };
        if (perMinute == null) return null;
        BigDecimal gross = basis.gross(perMinute);
        // A single fee outside the band of its feed's unit is not trusted with a number.
        if (gross.compareTo(MIN_PER_MINUTE) < 0 || gross.compareTo(MAX_PER_MINUTE) > 0) return null;
        return gross;
    }

    /**
     * The session minute an element starts at, or {@code null} for a restriction this class does not
     * understand — a time of day, a weekday, a power or energy range, or an end — which makes the tariff
     * uncertain. Zero-valued restrictions are how OCPDB writes "none".
     */
    private static Integer fromMinute(Map<String, Object> restrictions) {
        if (restrictions == null) return 0;
        int minute = 0;
        for (Map.Entry<String, Object> restriction : restrictions.entrySet()) {
            Object value = restriction.getValue();
            if (value == null || value instanceof Number number && number.doubleValue() == 0
                    || value instanceof Collection<?> values && values.isEmpty()
                    || value instanceof String text && text.isBlank()) continue;
            if (!"min_duration".equals(restriction.getKey()) || !(value instanceof Number seconds)) return null;
            minute = (int) Math.round(seconds.doubleValue() / 60);
        }
        return minute;
    }

    /**
     * The VAT rate as a fraction: zero when no tax is given, {@code null} for one that cannot be read. OCPDB
     * writes {@code "19"}, and a few records the fraction itself ({@code "0.19000000000000000222…"}); a rate
     * below 1 can only be the latter.
     */
    private static BigDecimal rate(List<Tax> taxes) {
        if (taxes == null || taxes.isEmpty()) return BigDecimal.ZERO;
        BigDecimal rate = null;
        for (Tax tax : taxes) {
            if (tax == null || tax.percentage() == null) continue;
            BigDecimal value;
            try {
                value = new BigDecimal(tax.percentage().trim());
            } catch (NumberFormatException _) {
                return null;
            }
            if (value.signum() == 0) continue;
            BigDecimal fraction = value.compareTo(BigDecimal.ONE) < 0 ? value : value.divide(HUNDRED);
            fraction = fraction.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
            if (rate != null && rate.compareTo(fraction) != 0) return null;
            rate = fraction;
        }
        return rate == null ? BigDecimal.ZERO : rate;
    }
}
