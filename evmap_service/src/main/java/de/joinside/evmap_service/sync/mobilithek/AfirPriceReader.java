package de.joinside.evmap_service.sync.mobilithek;

import com.fasterxml.jackson.databind.JsonNode;
import de.joinside.evmap_service.sync.SourceStation;
import de.joinside.evmap_service.vatbasis.TableBasis;
import de.joinside.evmap_service.vatbasis.VatBasisTable;
import de.joinside.evmap_service.vatbasis.VatEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import static de.joinside.evmap_service.sync.mobilithek.AfirSiteMapper.enumValue;
import static de.joinside.evmap_service.sync.mobilithek.AfirSiteMapper.field;
import static de.joinside.evmap_service.sync.mobilithek.AfirSiteMapper.items;
import static de.joinside.evmap_service.sync.mobilithek.AfirSiteMapper.text;

/**
 * Reads a refill point's ad-hoc {@code energyRate}s into gross prices — as delivered, and only where the gross amount
 * is established (ADR 0022, gap filler L6p).
 * <p>
 * What the static AFIR feeds publish, checked on every package of 2026-10-03, and how it is read:
 * <ul>
 *   <li><strong>VAT basis.</strong> Most feeds state per amount whether VAT is included ({@code taxIncluded},
 *       {@code taxRate}); an amount without the flag takes its rate's (Wirelane leaves it off the start fee).
 *       chargecloud states neither, so its basis comes from arithmetic evidence or the hand-kept operator table, as
 *       for OCPDB. Otherwise: no price.</li>
 *   <li><strong>Time.</strong> Fees per minute from a minute of the session on ({@code timeBasedApplicability}),
 *       capped ({@code priceCap}), and fees and energy prices by time of day and weekday
 *       ({@code overallPeriod.validPeriod}) are kept with their window. Clock times are local time as written: the
 *       offsets contradict the publishers' own texts (Wirelane: "außer zwischen 20–8 Uhr" for
 *       {@code 20:00+00:00–08:00+00:00}). Prices for dated slots ({@code overallEndTime}, evprice) are not read:
 *       they would be out of date within hours of a daily sync.</li>
 *   <li><strong>Several ad-hoc rates</strong> of one refill point differ by payment means; identical ones become
 *       one, different ones are all kept, labelled with their means.</li>
 *   <li><strong>Idle fees after charging</strong> ({@code priceType: other}, Monta) carry no amount, only "further
 *       fees possible".</li>
 * </ul>
 * Anything not understood — an energy price that changes after some minutes (Wirelane writes its blocking fee as one),
 * two prices for one moment, an unknown price type or applicability, a value outside the plausible band — makes the
 * whole refill point unpriced. A price shown wrongly is worse than none.
 * <p>
 * Not thread-safe; one instance per feed and run, sharing the run's table.
 */
final class AfirPriceReader {
    private static final Logger log = LoggerFactory.getLogger(AfirPriceReader.class);

    /** Counts what happened to the refill points that carry a rate, for the feed's summary line. */
    static final class Counters {
        int priced;
        int uncertain;
        int basisUnknown;
    }

    private static final String EUR = "EUR";
    private static final String AD_HOC = "adHoc";
    private static final BigDecimal MIN_ENERGY = new BigDecimal("0.05");
    private static final BigDecimal MAX_ENERGY = new BigDecimal("1.50");
    private static final BigDecimal MAX_SESSION = new BigDecimal("20");
    private static final BigDecimal MIN_PER_MINUTE = new BigDecimal("0.005");
    private static final BigDecimal MAX_PER_MINUTE = BigDecimal.ONE;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    /** Germany's standard rate, assumed for net-listed operators whose feed names none (chargecloud). */
    private static final BigDecimal GERMAN_VAT = new BigDecimal("0.19");
    /** A dated price that ended within this long marks a schedule of short slots, not last season's price. */
    private static final Duration SCHEDULE_HORIZON = Duration.ofDays(1);
    /** DATEX writes the VAT flag as a boolean, XML as its text. */
    private static final Map<String, Boolean> FLAGS = Map.of("true", Boolean.TRUE, "false", Boolean.FALSE);
    /** Publisher extensions, ignored where they are empty ({@code aegiEnergyPriceExtensionG: {}}). */
    private static final String EXTENSION = "ExtensionG";
    private static final Pattern CLOCK = Pattern.compile("(\\d{2}):(\\d{2})(?::(\\d{2}))?");
    private static final List<String> WEEK =
            List.of("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday");

    private final VatBasisTable table;
    private final String statedBy;
    final Counters counters = new Counters();

    /**
     * @param table    the run's operator table; an entry a price contradicts is suspended for the rest of the run
     * @param statedBy credited next to every price of the feed ("EnBW mobility+ AG und Co.KG via Mobilithek")
     */
    AfirPriceReader(VatBasisTable table, String statedBy) {
        this.table = table;
        this.statedBy = statedBy;
    }

    /**
     * @param point    the {@code aegiElectricChargingPoint} (or the refill point itself)
     * @param operator the station's operator as the feed names it, for the table
     * @param asOf     when the package was fetched: a rate without its own date is dated by it, and a rate that
     *                 only starts after it is not yet in force
     * @return the prices, empty when the refill point has none or any part of one is uncertain
     */
    List<SourceStation.SourcePrice> read(JsonNode point, String operator, Instant asOf) {
        List<JsonNode> rates = new ArrayList<>();
        for (JsonNode energy : items(field(point, "electricEnergy")))
            for (JsonNode rate : items(field(energy, "energyRate")))
                if (AD_HOC.equals(enumValue(field(rate, "ratePolicy")))) rates.add(rate);
        if (rates.isEmpty()) return List.of();

        List<SourceStation.SourcePrice> prices = new ArrayList<>();
        for (JsonNode rate : rates) {
            Outcome outcome = rate(rate, operator, asOf);
            if (outcome.price == null) {
                if (outcome.basisUnknown) counters.basisUnknown++;
                else counters.uncertain++;
                return List.of();
            }
            merge(prices, outcome.price);
        }
        counters.priced++;
        return prices;
    }

    /** Adds {@code price}, or joins its payment means to an earlier price with the same amounts. */
    private static void merge(List<SourceStation.SourcePrice> prices, SourceStation.SourcePrice price) {
        for (int i = 0; i < prices.size(); i++) {
            SourceStation.SourcePrice known = prices.get(i);
            if (!sameAmounts(known, price)) continue;
            Set<String> means = new LinkedHashSet<>(known.paymentMeans());
            means.addAll(price.paymentMeans());
            Instant observed = latest(known.observedAt(), price.observedAt());
            prices.set(i, new SourceStation.SourcePrice(known.currency(), known.energyPerKwh(), known.energyWindows(),
                    known.sessionFee(), known.timeFees(), known.free(), known.furtherFees(), observed,
                    List.copyOf(means), known.vatBasisStated(), known.statedBy()));
            return;
        }
        prices.add(price);
    }

    private static boolean sameAmounts(SourceStation.SourcePrice a, SourceStation.SourcePrice b) {
        return Objects.equals(a.currency(), b.currency()) && sameAmount(a.energyPerKwh(), b.energyPerKwh())
                && a.energyWindows().equals(b.energyWindows()) && sameAmount(a.sessionFee(), b.sessionFee())
                && a.timeFees().equals(b.timeFees()) && a.free() == b.free() && a.furtherFees() == b.furtherFees()
                && a.vatBasisStated() == b.vatBasisStated();
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    /** One rate read, or why not: {@code basisUnknown} when only the VAT basis is missing. */
    private record Outcome(SourceStation.SourcePrice price, boolean basisUnknown) {
        static final Outcome UNCERTAIN = new Outcome(null, false);
        static final Outcome BASIS_UNKNOWN = new Outcome(null, true);
    }

    /** One price component as published, with its applicability. */
    private record Component(String type, BigDecimal value, Boolean taxIncluded, BigDecimal rate, int fromMinute,
                             Integer toMinute, BigDecimal cap, SourceStation.TimeWindow window) {
    }

    private Outcome rate(JsonNode rate, String operator, Instant asOf) {
        List<String> currencies = StreamSupport.stream(items(field(rate, "applicableCurrency")).spliterator(), false)
                .map(AfirSiteMapper::text)
                .toList();
        if (!currencies.equals(List.of(EUR))) return Outcome.UNCERTAIN;

        List<Component> components = new ArrayList<>();
        for (JsonNode price : items(field(rate, "energyPrice"))) {
            Component component = component(price, asOf);
            if (component == null) return Outcome.UNCERTAIN;
            if (component != EXPIRED) components.add(component);
        }
        Tariff tariff = Tariff.of(components);
        if (tariff == null) return Outcome.UNCERTAIN;

        Basis basis = basis(components, tariff, operator);
        if (basis == null) return Outcome.BASIS_UNKNOWN;
        if (basis == Basis.INVALID) return Outcome.UNCERTAIN;
        Instant observed = instant(text(field(rate, "lastUpdated")));
        return tariff.price(basis, observed != null ? observed : asOf, paymentMeans(rate), statedBy);
    }

    private static List<String> paymentMeans(JsonNode rate) {
        Set<String> means = new LinkedHashSet<>();
        for (JsonNode payment : items(field(rate, "payment")))
            for (JsonNode value : items(field(payment, "paymentMeans"))) {
                String token = enumValue(value);
                if (token != null) means.add(token);
            }
        return List.copyOf(means);
    }

    /** {@code null} for a component this class does not understand, which makes the rate uncertain. */
    private static Component component(JsonNode price, Instant asOf) {
        String type = enumValue(field(price, "priceType"));
        BigDecimal value = decimal(text(field(price, "value")));
        if (type == null || value == null || value.signum() < 0) return null;
        if (!isEmpty(field(price, "energyBasedApplicability"))) return null;

        JsonNode minutes = field(price, "timeBasedApplicability");
        Integer from = integer(text(field(minutes, "fromMinute")));
        Integer to = integer(text(field(minutes, "toMinute")));
        int fromMinute = from == null ? 0 : from;
        // EnBW writes toMinute 0 for "open"; an end at or before the start is no end either.
        Integer toMinute = to == null || to <= fromMinute ? null : to;

        Applicability applicability = applicability(field(price, "overallPeriod"), asOf);
        if (applicability == null) return null;
        if (applicability == Applicability.EXPIRED) return EXPIRED;
        BigDecimal cap = decimal(text(field(price, "priceCap")));
        String flag = text(field(price, "taxIncluded"));
        return new Component(type, value, flag == null ? null : FLAGS.get(flag.trim().toLowerCase(Locale.ROOT)),
                fraction(decimal(text(field(price, "taxRate")))), fromMinute, toMinute,
                cap == null || cap.signum() == 0 ? null : cap, applicability.window);
    }

    private record Applicability(SourceStation.TimeWindow window) {
        /** A component whose period has ended: gridco keeps last season's prices next to the current ones. */
        static final Applicability EXPIRED = new Applicability(new SourceStation.TimeWindow("expired", "", List.of()));
    }

    /** Stands for a component that is no longer in force and is left out. */
    private static final Component EXPIRED = new Component("expired", BigDecimal.ZERO, null, null, 0, null, null, null);

    /**
     * When a component applies: always ({@code window == null}), or in one recurring period of the week;
     * {@link Applicability#EXPIRED} once its period ended more than a day ago (gridco keeps last season's prices).
     * {@code null} for what is not read: a dated slot that is running or ended within the day — evprice publishes
     * a new price every two hours, which a daily sync would show out of date —, a price not yet in force,
     * exceptions, more than one period.
     */
    private static Applicability applicability(JsonNode period, Instant asOf) {
        if (period == null) return new Applicability(null);
        if (field(period, "overallEndTime") != null) {
            Instant end = instant(text(field(period, "overallEndTime")));
            return end != null && end.isBefore(asOf.minus(SCHEDULE_HORIZON)) ? Applicability.EXPIRED : null;
        }
        Instant start = instant(text(field(period, "overallStartTime")));
        if (start != null && start.isAfter(asOf)) return null;
        if (!isEmpty(field(period, "exceptionPeriod"))) return null;

        List<JsonNode> valid = list(field(period, "validPeriod"));
        if (valid.isEmpty()) return new Applicability(null);
        if (valid.size() > 1) return null;
        return recurring(valid.getFirst());
    }

    /** One valid period of times of day and weekdays; {@code null} for anything else in it. */
    private static Applicability recurring(JsonNode period) {
        if (!holdsOnly(period, "recurringTimePeriodOfDay", "recurringDayWeekMonthPeriod")) return null;
        List<JsonNode> times = list(field(period, "recurringTimePeriodOfDay"));
        List<JsonNode> dayPeriods = list(field(period, "recurringDayWeekMonthPeriod"));
        if (times.size() > 1 || dayPeriods.size() > 1) return null;

        String from = "00:00";
        String to = "24:00";
        if (!times.isEmpty()) {
            from = clock(text(field(times.getFirst(), "startTimeOfPeriod")), false);
            to = clock(text(field(times.getFirst(), "endTimeOfPeriod")), true);
            if (from == null || to == null || from.equals(to)) return null;
        }
        Optional<List<String>> days = dayPeriods.isEmpty() ? Optional.of(List.of()) : days(dayPeriods.getFirst());
        if (days.isEmpty()) return null;
        boolean always = from.equals("00:00") && to.equals("24:00") && days.get().isEmpty();
        return new Applicability(always ? null : new SourceStation.TimeWindow(from, to, days.get()));
    }

    /** Whether {@code node} has nothing but the named fields and empty extensions. */
    private static boolean holdsOnly(JsonNode node, String... allowed) {
        if (node == null) return true;
        Set<String> known = Set.of(allowed);
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!known.contains(name) && !name.endsWith(EXTENSION) && !isEmpty(node.get(name))) return false;
        }
        return true;
    }

    /**
     * {@code HH:mm} of a published clock time, offset ignored. An end on the last second or minute of an hour
     * ({@code 19:59:59}, {@code 23:59:00}) is the full hour, and midnight as an end is {@code 24:00}.
     */
    static String clock(String published, boolean end) {
        if (published == null) return null;
        Matcher matcher = CLOCK.matcher(published.trim());
        if (!matcher.lookingAt()) return null;
        int hour = Integer.parseInt(matcher.group(1));
        int minute = Integer.parseInt(matcher.group(2));
        int second = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        if (hour > 23 || minute > 59 || second > 59) return null;
        if (end) {
            if (second == 59 || (minute == 59 && hour == 23)) minute++;
            if (minute == 60) {
                minute = 0;
                hour++;
            }
            if (hour == 0 && minute == 0) hour = 24;
        }
        return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
    }

    /** The applicable weekdays in week order, empty for all seven; nothing for anything else (months, dates). */
    private static Optional<List<String>> days(JsonNode period) {
        JsonNode dayWeekMonth = field(period, "comDayWeekMonth");
        if (!holdsOnly(period, "comDayWeekMonth") || !holdsOnly(dayWeekMonth, "applicableDay")) return Optional.empty();
        Set<String> days = new TreeSet<>(Comparator.comparingInt(WEEK::indexOf));
        for (JsonNode day : items(field(dayWeekMonth, "applicableDay"))) {
            String value = enumValue(day);
            String normalized = value == null ? null : value.toLowerCase(Locale.ROOT);
            if (!WEEK.contains(normalized)) return Optional.empty();
            days.add(normalized);
        }
        if (days.isEmpty()) return Optional.empty();
        return Optional.of(days.size() == WEEK.size() ? List.of() : List.copyOf(days));
    }

    // --- VAT basis -------------------------------------------------------------------------------------

    /**
     * How the published amounts become gross.
     *
     * @param cents  every amount rounded to whole cents: the amounts of a table operator, whose check compared them
     *               with a price page, and price pages charge whole cents (ADR 0022, 5r). Stated and proven bases keep
     *               the feed's precision.
     * @param stated the feed stated the basis
     */
    private record Basis(BigDecimal factor, boolean cents, boolean stated) {
        static final Basis INVALID = new Basis(BigDecimal.ZERO, false, false);

        BigDecimal gross(BigDecimal amount) {
            if (amount == null) return null;
            BigDecimal gross = amount.multiply(factor).setScale(cents ? 2 : 4, RoundingMode.HALF_UP);
            return plain(gross);
        }
    }

    /** The basis, {@link Basis#INVALID} for a contradictory statement, {@code null} when it cannot be established. */
    private Basis basis(List<Component> components, Tariff tariff, String operator) {
        Set<Boolean> flags = new LinkedHashSet<>();
        Set<BigDecimal> rates = new LinkedHashSet<>();
        for (Component component : components) {
            if (component.taxIncluded != null) flags.add(component.taxIncluded);
            if (component.rate != null && component.rate.signum() > 0) rates.add(component.rate);
        }
        if (flags.size() > 1 || rates.size() > 1) return Basis.INVALID;
        BigDecimal rate = rates.isEmpty() ? null : rates.iterator().next();
        if (flags.isEmpty()) return inferred(tariff.energyPerKwh, rate, operator);
        if (Boolean.TRUE.equals(flags.iterator().next())) return new Basis(BigDecimal.ONE, false, true);
        return rate == null ? Basis.INVALID : new Basis(BigDecimal.ONE.add(rate), false, true);
    }

    /** No flag (chargecloud): what OCPDB's tariffs get — evidence, then the hand-kept table (ADR 0022, 5r). */
    private Basis inferred(BigDecimal energy, BigDecimal rate, String operator) {
        if (energy == null || energy.signum() == 0) return null;
        if (rate != null && VatEvidence.provenGross(energy, rate) != null)
            return new Basis(BigDecimal.ONE.add(rate), false, false);
        BigDecimal netRate = rate != null ? rate : GERMAN_VAT;
        TableBasis listed = table.basisOf(operator);
        if (listed != TableBasis.UNCHECKED && contradicts(listed, energy, netRate)) {
            if (table.suspend(operator))
                log.warn("Mobilithek price {} of {} contradicts its VAT basis entry {}; entry suspended for this run "
                        + "(ADR 0022, docs/operations/price-basis-operators.md)", energy, operator, listed);
            return null;
        }
        return switch (listed) {
            case NET -> new Basis(BigDecimal.ONE.add(netRate), true, false);
            case GROSS -> new Basis(BigDecimal.ONE, true, false);
            case UNCHECKED -> null;
        };
    }

    /** As for OCPDB: a gross-listed price the arithmetic proves net, or a net-listed one that is gross already. */
    private static boolean contradicts(TableBasis listed, BigDecimal energy, BigDecimal rate) {
        boolean provenNet = VatEvidence.provenGross(energy, rate) != null;
        return listed == TableBasis.GROSS ? provenNet : !provenNet && VatEvidence.looksGross(energy, rate);
    }

    // --- one rate's amounts ----------------------------------------------------------------------------

    /** The amounts of one rate, net or gross as published; {@code null} from {@link #of} when anything is uncertain. */
    private static final class Tariff {
        BigDecimal energyPerKwh;
        final Map<SourceStation.TimeWindow, BigDecimal> energyWindows = new LinkedHashMap<>();
        BigDecimal sessionFee;
        final List<Component> fees = new ArrayList<>();
        boolean furtherFees;

        static Tariff of(List<Component> components) {
            Tariff tariff = new Tariff();
            List<Component> energies = new ArrayList<>();
            List<Component> perMinute = new ArrayList<>();
            Set<BigDecimal> sessions = new LinkedHashSet<>();
            for (Component component : components) {
                switch (component.type) {
                    case "pricePerKWh" -> energies.add(component);
                    case "pricePerMinute" -> perMinute.add(component);
                    case "flatRate", "basePrice" -> {
                        if (component.value.signum() == 0) continue;
                        if (component.fromMinute != 0 || component.toMinute != null || component.window != null)
                            return null;
                        sessions.add(component.value);
                    }
                    case "other" -> tariff.furtherFees |= component.value.signum() > 0;
                    default -> {
                        return null;
                    }
                }
            }
            if (sessions.size() > 1 || !tariff.readEnergy(energies) || !tariff.readFees(perMinute)) return null;
            tariff.sessionFee = sessions.isEmpty() ? null : sessions.iterator().next();
            return tariff;
        }

        /**
         * One price whatever the hour or minute, or one per window of the day. An energy price that depends on the
         * minute of the session, or a windowed price next to an unwindowed one, is not understood.
         */
        private boolean readEnergy(List<Component> energies) {
            Set<BigDecimal> values = new TreeSet<>();
            energies.forEach(energy -> values.add(energy.value));
            if (values.isEmpty()) return false;
            if (values.size() == 1) {
                energyPerKwh = values.iterator().next();
                return true;
            }
            for (Component energy : energies) {
                if (energy.fromMinute != 0 || energy.toMinute != null || energy.window == null) return false;
                BigDecimal known = energyWindows.putIfAbsent(energy.window, energy.value);
                if (known != null && known.compareTo(energy.value) != 0) return false;
            }
            return true;
        }

        /**
         * Fees by the minute they start at and their window, each until the minute a zero fee of its window takes
         * over (chargecloud: 0,10 €/min from minute 240 to 390, a cap by another name). Two different fees for one
         * moment — the same minute and an overlapping window — are not understood; nor is a fee whose own end
         * ({@code toMinute}) nothing takes over from: Wirelane writes "ab 120 Min." as minute 0 to 120.
         */
        private boolean readFees(List<Component> perMinute) {
            if (perMinute.stream().anyMatch(fee -> contradicts(fee, perMinute) || endsByItself(fee, perMinute)))
                return false;
            Set<String> seen = new LinkedHashSet<>();
            for (Component fee : perMinute)
                if (fee.value.signum() != 0 && seen.add(fee.fromMinute + "|" + fee.window + "|" + fee.cap))
                    fees.add(new Component(fee.type, fee.value, fee.taxIncluded, fee.rate, fee.fromMinute,
                            until(fee, perMinute), fee.cap, fee.window));
            fees.sort(Comparator.comparingInt(Component::fromMinute)
                    .thenComparing(fee -> fee.window == null ? "" : fee.window.from()));
            return true;
        }

        /** Another fee from the same minute, in an overlapping window, with a different amount. */
        private static boolean contradicts(Component fee, List<Component> perMinute) {
            return perMinute.stream().anyMatch(other -> other != fee && other.fromMinute == fee.fromMinute
                    && overlap(other.window, fee.window) && other.value.compareTo(fee.value) != 0);
        }

        /** A fee with its own end that no other fee of its window takes over from. */
        private static boolean endsByItself(Component fee, List<Component> perMinute) {
            return fee.toMinute != null && fee.value.signum() > 0 && perMinute.stream().noneMatch(next ->
                    next.fromMinute == fee.toMinute && overlap(next.window, fee.window));
        }

        /** The minute a zero fee of the same window takes over, if the next fee of that window is one. */
        private static Integer until(Component fee, List<Component> perMinute) {
            return perMinute.stream()
                    .filter(next -> next.fromMinute > fee.fromMinute && Objects.equals(next.window, fee.window))
                    .min(Comparator.comparingInt(Component::fromMinute))
                    .filter(next -> next.value.signum() == 0)
                    .map(Component::fromMinute)
                    .orElse(null);
        }

        private static boolean inBand(BigDecimal value, BigDecimal min, BigDecimal max) {
            return value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
        }

        /** Unwindowed overlaps everything; two different windows are taken as the publisher's split of the day. */
        private static boolean overlap(SourceStation.TimeWindow a, SourceStation.TimeWindow b) {
            return a == null || b == null || a.equals(b);
        }

        Outcome price(Basis basis, Instant observedAt, List<String> paymentMeans, String statedBy) {
            BigDecimal energy = basis.gross(energyPerKwh);
            List<SourceStation.EnergyWindow> windows = new ArrayList<>();
            for (Map.Entry<SourceStation.TimeWindow, BigDecimal> window : energyWindows.entrySet()) {
                BigDecimal gross = basis.gross(window.getValue());
                if (gross.signum() != 0 && !inBand(gross, MIN_ENERGY, MAX_ENERGY)) return Outcome.UNCERTAIN;
                windows.add(new SourceStation.EnergyWindow(gross, window.getKey()));
            }
            windows.sort(Comparator.comparing(window -> window.window().from()));
            if (energy != null && energy.signum() != 0 && !inBand(energy, MIN_ENERGY, MAX_ENERGY)) return Outcome.UNCERTAIN;
            BigDecimal session = basis.gross(sessionFee);
            if (session != null && session.compareTo(MAX_SESSION) > 0) return Outcome.UNCERTAIN;

            if (energy != null && energy.signum() == 0 && session == null && fees.isEmpty())
                return new Outcome(new SourceStation.SourcePrice(EUR, null, List.of(), null, List.of(), true,
                        furtherFees, observedAt, paymentMeans, basis.stated(), statedBy), false);

            List<SourceStation.TimeFee> timeFees = new ArrayList<>();
            for (Component fee : fees) {
                BigDecimal gross = basis.gross(fee.value);
                // A fee outside the per-minute band is not trusted with a number (eRound's 0,0017 €).
                timeFees.add(new SourceStation.TimeFee(fee.fromMinute, fee.toMinute,
                        inBand(gross, MIN_PER_MINUTE, MAX_PER_MINUTE) ? gross : null, basis.gross(fee.cap), fee.window));
            }
            return new Outcome(new SourceStation.SourcePrice(EUR, windows.isEmpty() ? energy : null, windows, session,
                    timeFees, false, furtherFees, observedAt, paymentMeans, basis.stated(), statedBy), false);
        }
    }

    // --- values ----------------------------------------------------------------------------------------

    /**
     * A published amount, to six decimals: gridco writes {@code float} noise (0,4499999881), and no price has more.
     */
    static BigDecimal decimal(String text) {
        if (text == null) return null;
        try {
            return plain(new BigDecimal(text.trim()).setScale(6, RoundingMode.HALF_UP));
        } catch (NumberFormatException _) {
            return null;
        }
    }

    private static BigDecimal plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    /** A VAT rate as a fraction: feeds write 19 or 19.0; a value below 1 can only be the fraction itself. */
    private static BigDecimal fraction(BigDecimal value) {
        if (value == null) return null;
        BigDecimal fraction = value.compareTo(BigDecimal.ONE) < 0 ? value : value.divide(HUNDRED);
        return plain(fraction.setScale(4, RoundingMode.HALF_UP));
    }

    private static Integer integer(String text) {
        BigDecimal value = decimal(text);
        return value == null ? null : value.intValue();
    }

    private static Instant instant(String text) {
        if (text == null) return null;
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    private static List<JsonNode> list(JsonNode node) {
        List<JsonNode> list = new ArrayList<>();
        for (JsonNode item : items(node)) if (!isEmpty(item)) list.add(item);
        return list;
    }

    /** Missing, null, blank, or a list or object of nothing but such — {@code {}} and empty extensions included. */
    private static boolean isEmpty(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return true;
        if (node.isValueNode()) return node.asText().isBlank();
        for (JsonNode child : node) if (!isEmpty(child)) return false;
        return true;
    }
}
