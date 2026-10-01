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
        for (Tariff tariff : tariffs)
            if (tariff.source() != null)
                prices.computeIfAbsent(tariff.source(), key -> new ArrayList<>()).addAll(timePrices(tariff));
        Map<String, TimeUnit> units = new HashMap<>();
        prices.forEach((source, values) -> {
            if (values.isEmpty()) return;
            values.sort(null);
            units.put(source, unitOf(values.get(values.size() / 2)));
        });
        return units;
    }

    /** The non-zero {@code TIME} prices of a tariff. */
    private static List<BigDecimal> timePrices(Tariff tariff) {
        if (tariff.elements() == null) return List.of();
        return tariff.elements().stream()
                .filter(element -> element.priceComponents() != null)
                .flatMap(element -> element.priceComponents().stream())
                .filter(component -> "TIME".equals(component.type()) && component.price() != null
                        && component.price().signum() > 0)
                .map(PriceComponent::price)
                .toList();
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

        TableBasis basisOf(String operatorName) {
            if (operatorName == null) return TableBasis.UNCHECKED;
            String key = key(operatorName);
            if (netOperators.contains(key)) return TableBasis.NET;
            if (grossOperators.contains(key)) return TableBasis.GROSS;
            return TableBasis.UNCHECKED;
        }
    }

    /** What the hand-kept table says about an operator's prices. */
    enum TableBasis { NET, GROSS, UNCHECKED }

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

        Components components = new Components();
        for (Element element : tariff.elements())
            if (!components.add(element)) return null;
        if (!components.isConsistent()) return null;
        if (tariff.taxIncluded() != null) components.included = Set.of(tariff.taxIncluded());

        BigDecimal energy = components.energies.iterator().next();
        // Nothing to pay, whatever the VAT basis: free.
        if (energy.signum() == 0 && components.flats.isEmpty() && components.timeFees.isEmpty())
            return new AdHocPrice("EUR", null, null, List.of(), true, components.furtherFees, tariff.lastUpdated());

        BigDecimal rate = components.rates.isEmpty() ? null : components.rates.iterator().next();
        Basis basis = basis(energy, rate, components.included, table.basisOf(operatorName));
        if (basis == null || !inBand(basis.energy, MIN_ENERGY, MAX_ENERGY)) return null;

        BigDecimal session = components.flats.isEmpty() ? null : basis.gross(components.flats.iterator().next());
        if (session != null && session.compareTo(MAX_SESSION) > 0) return null;

        List<AdHocPrice.TimeFee> fees = new ArrayList<>();
        components.timeFees.forEach((minute, price) -> fees.add(new AdHocPrice.TimeFee(minute, perMinute(price, unit, basis))));
        return new AdHocPrice("EUR", basis.energy, session, fees, false, components.furtherFees, tariff.lastUpdated());
    }

    private static boolean inBand(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
    }

    /** The price components of one tariff, collected element by element; anything not understood rejects it. */
    private static final class Components {
        final Set<BigDecimal> energies = new LinkedHashSet<>();
        final Set<BigDecimal> flats = new LinkedHashSet<>();
        final Map<Integer, BigDecimal> timeFees = new TreeMap<>();
        final Set<BigDecimal> rates = new LinkedHashSet<>();
        Set<Boolean> included = new LinkedHashSet<>();
        boolean furtherFees;

        boolean add(Element element) {
            if (element.priceComponents() == null) return true;
            Integer fromMinute = fromMinute(element.restrictions());
            if (fromMinute == null) return false;
            return element.priceComponents().stream().allMatch(component -> add(component, fromMinute));
        }

        private boolean add(PriceComponent component, int fromMinute) {
            BigDecimal price = component.price();
            if (price == null || price.signum() < 0) return false;
            BigDecimal rate = rate(component.taxes());
            if (rate == null) return false;
            if (rate.signum() > 0) rates.add(rate);
            if (component.taxIncluded() != null) included.add(component.taxIncluded());
            String type = component.type() == null ? "" : component.type();
            return switch (type) {
                case "ENERGY" -> addFromStart(energies, fromMinute, price);
                case "FLAT" -> price.signum() == 0 || addFromStart(flats, fromMinute, price);
                case "TIME" -> addTimeFee(fromMinute, price);
                case "PARKING_TIME" -> {
                    furtherFees |= price.signum() > 0;
                    yield true;
                }
                default -> false;
            };
        }

        /** An energy price or start fee that changes after some minutes is a second price; not shown. */
        private static boolean addFromStart(Set<BigDecimal> into, int fromMinute, BigDecimal price) {
            if (fromMinute != 0) return false;
            into.add(price.stripTrailingZeros());
            return true;
        }

        /** Two different fees from the same minute are two prices for one moment: uncertain. */
        private boolean addTimeFee(int fromMinute, BigDecimal price) {
            if (price.signum() == 0) return true;
            BigDecimal previous = timeFees.putIfAbsent(fromMinute, price.stripTrailingZeros());
            return previous == null || previous.compareTo(price) == 0;
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
                if (isAbsent(value)) continue;
                if (!"min_duration".equals(restriction.getKey()) || !(value instanceof Number seconds)) return null;
                minute = (int) Math.round(seconds.doubleValue() / 60);
            }
            return minute;
        }

        private static boolean isAbsent(Object value) {
            return value == null
                    || value instanceof Number number && number.doubleValue() == 0
                    || value instanceof Collection<?> values && values.isEmpty()
                    || value instanceof String text && text.isBlank();
        }

        /**
         * The VAT rate as a fraction: zero when no tax is given, {@code null} for one that cannot be read. OCPDB
         * writes {@code "19"}, and a few records the fraction itself ({@code "0.19000000000000000222…"}); a rate
         * below 1 can only be the latter.
         */
        private static BigDecimal rate(List<Tax> taxes) {
            if (taxes == null || taxes.isEmpty()) return BigDecimal.ZERO;
            Set<BigDecimal> rates = new LinkedHashSet<>();
            try {
                taxes.stream()
                        .filter(tax -> tax != null && tax.percentage() != null)
                        .map(tax -> new BigDecimal(tax.percentage().trim()))
                        .filter(value -> value.signum() != 0)
                        .map(Components::fraction)
                        .forEach(rates::add);
            } catch (NumberFormatException _) {
                return null;
            }
            if (rates.size() > 1) return null;
            return rates.isEmpty() ? BigDecimal.ZERO : rates.iterator().next();
        }

        private static BigDecimal fraction(BigDecimal value) {
            BigDecimal fraction = value.compareTo(BigDecimal.ONE) < 0 ? value : value.divide(HUNDRED);
            return fraction.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        }

        boolean isConsistent() {
            return energies.size() == 1 && flats.size() <= 1 && rates.size() <= 1 && included.size() <= 1;
        }
    }

    /** How net amounts become gross, once the basis is known. */
    private record Basis(BigDecimal energy, BigDecimal factor) {
        BigDecimal gross(BigDecimal amount) {
            return amount.multiply(factor).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        }
    }

    private static Basis basis(BigDecimal energy, BigDecimal rate, Set<Boolean> included, TableBasis table) {
        if (!included.isEmpty()) {
            if (Boolean.TRUE.equals(included.iterator().next())) return new Basis(energy, BigDecimal.ONE);
            return rate == null ? null : net(energy, rate);
        }
        if (rate != null) {
            BigDecimal proven = provenGross(energy, rate);
            if (proven != null) return new Basis(proven, BigDecimal.ONE.add(rate));
        }
        return switch (table) {
            case NET -> rate == null ? null : net(energy, rate);
            case GROSS -> new Basis(energy, BigDecimal.ONE);
            case UNCHECKED -> null;
        };
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
        return inBand(gross, MIN_PER_MINUTE, MAX_PER_MINUTE) ? gross : null;
    }

}
