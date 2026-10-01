package de.joinside.evmap_service.sync.irve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.joinside.evmap_service.sync.SourceStation.SourcePrice;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the IRVE register's free-text {@code tarification} into a price — only where it is certain.
 * <p>
 * The column is prose more often than a price: of 54.975 filled rows in the 2026-10-01 edition, 13.108
 * say that prices vary, thousands are a link, "Inconnu" or "Payant", and the rest mixes units, VAT
 * bases and mojibake. The product owner's rule is that a price shown wrongly is worse than none (ADR 0022),
 * so this class is built the other way round from a lenient parser: a text is accepted only when every part of
 * it is understood, and anything else answers {@code null}. The three shapes it reads:
 * <ul>
 *   <li><strong>Plain text</strong> ("0,29€ / kWh", "59 cts/kWh", "Bornes rapides: 2€ + 0.59€ / kWh"). No
 *       VAT statement means gross — French consumer prices must be shown TTC — and an explicit {@code HT}
 *       is converted at the standard rate.</li>
 *   <li><strong>An OCPI-like generated format</strong> of two operators ("entre 08:00 et 20:00 :
 *       0.30916667€ par kwh de charge, …"). It maps onto OCPI price components, which are net, and says
 *       nothing about VAT. Its energy price is converted only where the arithmetic proves it net: the value
 *       is not a round amount and × 1,2 lands on whole cents (or, with six decimals or more, on a tenth of
 *       a cent). Its time and idle fees, bound to time windows, are not shown.</li>
 *   <li><strong>DRIVECO's JSON</strong>, read structurally.</li>
 * </ul>
 */
final class IrvePriceText {
    /** France's standard VAT rate, which applies to public charging. */
    static final BigDecimal FRENCH_VAT = new BigDecimal("0.20");
    private static final BigDecimal GROSS_FACTOR = BigDecimal.ONE.add(FRENCH_VAT);
    private static final String EUR = "EUR";

    /** A gross energy price outside this band is a typo or a different unit, not a price. */
    private static final BigDecimal MIN_ENERGY = new BigDecimal("0.05");
    private static final BigDecimal MAX_ENERGY = new BigDecimal("1.50");
    /**
     * Per-minute fees beyond these are another unit (per hour, per session) or a typo: 0,79 €/min would be
     * 47 € an hour. The highest real one in the file is Freshmile's fast-charging 0,10 €/min.
     */
    private static final BigDecimal MIN_TIME = new BigDecimal("0.005");
    private static final BigDecimal MAX_TIME = new BigDecimal("0.50");
    private static final BigDecimal MAX_SESSION = new BigDecimal("20");

    private static final String NUMBER = "(\\d+(?:[.,]\\d+)?)";
    private static final String EURO = "(?:€|eur|euros?|e)";
    private static final String VAT = "(ttc|htva|ht)";

    private static final Pattern ENERGY = Pattern.compile(
            "^" + NUMBER + " ?" + EURO + " ?" + VAT + "? ?(?:/|par|le|au) ?kwh ?" + VAT + "?$");
    private static final Pattern ENERGY_CENTS = Pattern.compile(
            "^" + NUMBER + " ?(?:cts?|c€|centimes?) ?" + VAT + "? ?(?:/|par) ?kwh ?" + VAT + "?$");
    private static final Pattern SESSION = Pattern.compile(
            "^" + NUMBER + " ?" + EURO + " ?" + VAT + "? ?(?:/ ?session|par session|la session|à la connexion"
                    + "|par accès|de co[uû]t fixe par session de recharge)$");
    private static final Pattern TIME = Pattern.compile(
            "^" + NUMBER + " ?" + EURO + " ?" + VAT + "? ?(?:/|par|à la) ?(?:min|mn|minute)$");
    private static final Pattern MONEY = Pattern.compile("^" + NUMBER + " ?" + EURO + " ?" + VAT + "?$");

    /** A short label naming the kind of charge point the row already describes. */
    private static final Pattern LABEL = Pattern.compile(
            "^(?:ac|dc|hpc|charge normale|charge rapide|bornes? (?:ultra )?rapides?)\\s*:?\\s*");
    private static final Pattern NON_SUBSCRIBERS = Pattern.compile("\\s*pour les non[- ]abonn[ée]e?s\\s*\\.?$");
    private static final Pattern PARTS = Pattern.compile("\\s*\\+\\s*|,\\s+(?=\\d)|\\s+et\\s+(?=\\d)");

    private static final Pattern GENERATED = Pattern.compile("par kwh de charge");
    private static final Pattern GENERATED_ENERGY = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*\\S?\\s*par kwh de charge");
    private static final Pattern GENERATED_OTHER = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*\\S?\\s*par heure|prix de d\\S+part (\\d+(?:\\.\\d+)?)");

    private static final ObjectMapper JSON = new ObjectMapper();

    private IrvePriceText() {
    }

    /**
     * @param text       the {@code tarification} column, possibly empty
     * @param free       the {@code gratuit} column read as a flag
     * @param observedAt the row's {@code date_maj}, carried onto the price
     * @return the price, or {@code null} when the row states none this class is certain about
     */
    static SourcePrice parse(String text, boolean free, Instant observedAt) {
        String normalized = normalize(text);
        SourcePrice price = normalized.isEmpty() ? null : parseText(text.trim(), normalized, observedAt);
        if (free) {
            // A row that is free and names an amount contradicts itself; neither is shown.
            if (price != null && !price.free()) return null;
            return SourcePrice.freeOfCharge(observedAt);
        }
        return price;
    }

    private static SourcePrice parseText(String raw, String text, Instant observedAt) {
        if (text.equals("gratuit") || text.equals("gratuite")) return SourcePrice.freeOfCharge(observedAt);
        if (raw.startsWith("{")) return parseDriveco(raw, observedAt);
        if (GENERATED.matcher(text).find()) return parseGenerated(text, observedAt);
        return parsePlain(text, observedAt);
    }

    /** Repairs the one mojibake that is unambiguous and folds case and spacing. */
    static String normalize(String text) {
        if (text == null) return "";
        String repaired = text.replace("â‚¬", "€").replace(' ', ' ');
        return repaired.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    // --- plain text -----------------------------------------------------------------------------------

    private static SourcePrice parsePlain(String text, Instant observedAt) {
        String body = NON_SUBSCRIBERS.matcher(text).replaceFirst("");
        if (body.endsWith(".")) body = body.substring(0, body.length() - 1).trim();
        body = LABEL.matcher(body).replaceFirst("");

        List<String> parts = new ArrayList<>();
        for (String part : PARTS.split(body)) if (!part.isBlank()) parts.add(part.trim());
        if (parts.isEmpty()) return null;

        BigDecimal energy = null;
        BigDecimal session = null;
        BigDecimal time = null;
        Set<String> vat = new LinkedHashSet<>();
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            Matcher m;
            if (energy == null && (m = ENERGY.matcher(part)).matches()) {
                energy = amount(m.group(1));
                note(vat, m.group(2), m.group(3));
            } else if (energy == null && (m = ENERGY_CENTS.matcher(part)).matches()) {
                BigDecimal cents = amount(m.group(1));
                // "0,35cts/kWh" is either 0,35 ct or 35 ct; there is no telling which.
                if (cents == null || cents.compareTo(BigDecimal.ONE) < 0) return null;
                energy = cents.movePointLeft(2);
                note(vat, m.group(2), m.group(3));
            } else if (session == null && (m = SESSION.matcher(part)).matches()) {
                session = amount(m.group(1));
                note(vat, m.group(2), null);
            } else if (time == null && (m = TIME.matcher(part)).matches()) {
                time = amount(m.group(1));
                note(vat, m.group(2), null);
            } else if (session == null && i == 0 && parts.size() > 1 && (m = MONEY.matcher(part)).matches()) {
                // "2€ + 0.59€ / kWh": a bare amount leading a sum is the start fee.
                session = amount(m.group(1));
                note(vat, m.group(2), null);
            } else {
                return null;
            }
        }
        if (energy == null && time == null) return null;
        if (vat.size() > 1) return null;

        boolean net = !vat.isEmpty() && vat.iterator().next().startsWith("ht");
        if (!net && (tooPrecise(energy) || tooPrecise(session) || tooPrecise(time))) return null;
        return plausible(gross(energy, net), gross(session, net), gross(time, net), false, observedAt);
    }

    private static void note(Set<String> vat, String first, String second) {
        if (first != null) vat.add(first.startsWith("ht") ? "ht" : first);
        if (second != null) vat.add(second.startsWith("ht") ? "ht" : second);
    }

    /** More than three decimals on a price that does not say it is net is a computed value of unknown basis. */
    private static boolean tooPrecise(BigDecimal value) {
        return value != null && value.stripTrailingZeros().scale() > 3;
    }

    // --- generated format -----------------------------------------------------------------------------

    private static SourcePrice parseGenerated(String text, Instant observedAt) {
        Set<String> energies = new LinkedHashSet<>();
        Matcher m = GENERATED_ENERGY.matcher(text);
        while (m.find()) energies.add(m.group(1));
        if (energies.size() != 1) return null;

        BigDecimal gross = provenNet(new BigDecimal(energies.iterator().next()));
        if (gross == null) return null;

        boolean furtherFees = false;
        Matcher other = GENERATED_OTHER.matcher(text);
        while (other.find()) {
            String value = other.group(1) != null ? other.group(1) : other.group(2);
            if (new BigDecimal(value).signum() > 0) furtherFees = true;
        }
        return plausible(gross, null, null, furtherFees, observedAt);
    }

    /**
     * The gross price when {@code net} is demonstrably a net amount, else {@code null}.
     * <p>
     * A round value (two decimals or fewer) could be either and proves nothing. A value with more decimals
     * is net when × 1,2 lands on whole cents within the rounding error of the decimals it was written with
     * — or, for six decimals or more, where that error is negligible, on a tenth of a cent
     * (0,30916667 → 0,371).
     */
    static BigDecimal provenNet(BigDecimal net) {
        int decimals = net.stripTrailingZeros().scale();
        if (decimals <= 2) return null;
        BigDecimal gross = net.multiply(GROSS_FACTOR);
        BigDecimal tolerance = new BigDecimal("0.6").movePointLeft(decimals);
        BigDecimal cents = gross.setScale(2, RoundingMode.HALF_UP);
        if (gross.subtract(cents).abs().compareTo(tolerance) <= 0) return cents;
        BigDecimal millis = gross.setScale(3, RoundingMode.HALF_UP);
        if (decimals >= 6 && gross.subtract(millis).abs().compareTo(tolerance) <= 0) return millis;
        return null;
    }

    // --- DRIVECO JSON ---------------------------------------------------------------------------------

    private static SourcePrice parseDriveco(String raw, Instant observedAt) {
        JsonNode tariff;
        try {
            tariff = JSON.readTree(raw);
        } catch (IOException _) {
            return null;
        }
        if (tariff == null || !tariff.isObject() || !tariff.has("energyPrice")) return null;
        if (tariff.path("hasDynamicTarif").asBoolean(false) || tariff.path("ecoHour").asBoolean(false)) return null;
        if (tariff.path("matrix").isArray() && !tariff.path("matrix").isEmpty()) return null;
        if (tariff.path("minimumBilling").asDouble(0) > 0) return null;

        BigDecimal energy = tariff.path("energyPrice").decimalValue();
        BigDecimal fixed = tariff.path("fixedPrice").decimalValue();
        if (tooPrecise(energy) || tooPrecise(fixed)) return null;

        boolean overstay = false;
        for (JsonNode fee : tariff.path("matrixOSF"))
            if (fee.path("price").asDouble(0) > 0) overstay = true;
        return plausible(energy, fixed.signum() > 0 ? fixed : null, null, overstay, observedAt);
    }

    // --- shared ---------------------------------------------------------------------------------------

    private static BigDecimal amount(String value) {
        try {
            return new BigDecimal(value.replace(',', '.'));
        } catch (NumberFormatException _) {
            return null;
        }
    }

    private static BigDecimal gross(BigDecimal value, boolean net) {
        return value == null || !net ? value : value.multiply(GROSS_FACTOR).setScale(4, RoundingMode.HALF_UP);
    }

    private static SourcePrice plausible(BigDecimal energy, BigDecimal session, BigDecimal time,
                                         boolean furtherFees, Instant observedAt) {
        if (energy != null && (energy.compareTo(MIN_ENERGY) < 0 || energy.compareTo(MAX_ENERGY) > 0)) return null;
        if (time != null && (time.compareTo(MIN_TIME) < 0 || time.compareTo(MAX_TIME) > 0)) return null;
        if (session != null && (session.signum() < 0 || session.compareTo(MAX_SESSION) > 0)) return null;
        if (session != null && session.signum() == 0) session = null;
        return new SourcePrice(EUR, strip(energy), strip(session), strip(time), false, furtherFees, observedAt);
    }

    private static BigDecimal strip(BigDecimal value) {
        if (value == null) return null;
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0, RoundingMode.UNNECESSARY) : stripped;
    }
}
