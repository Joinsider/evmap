package de.joinside.evmap_service.sync.irve;

import de.joinside.evmap_service.sync.ConnectorTypes;
import de.joinside.evmap_service.sync.SourceStation;
import de.joinside.evmap_service.sync.support.CsvColumns;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns the French consolidated IRVE file into {@link SourceStation}s.
 * <p>
 * Split out of the adapter so the mapping is testable against a fixture without downloading 162 MB.
 * The published shape (verified against the 2026-07-29 consolidation, schema 2.3.1, 231.647 rows /
 * 64.251 stations):
 * <ul>
 *   <li>UTF-8, {@code ,}-separated, RFC4180 quoting, one header row and no preamble.</li>
 *   <li><strong>One row per <em>point de charge</em>, not per station.</strong> Rows belonging to one
 *       station share {@code id_station_itinerance} but are <em>not</em> contiguous — 48.080 of the
 *       64.251 stations are re-entered later in the file. That is what forces the grouping below to be
 *       a map rather than a running comparison against the previous row.</li>
 *   <li>Connectors are five boolean columns rather than labels, so the canonical type comes from the
 *       column, not from {@link ConnectorTypes#normalize}.</li>
 *   <li>No operational status column exists in the schema, so availability is always {@code null} —
 *       honest, and different from claiming everything works.</li>
 * </ul>
 * <p>
 * The file is a consolidation of thousands of separately published municipal and operator files, and
 * reads like it: booleans arrive as {@code true/True/TRUE/1} and {@code false/False/FALSE/0} in the
 * same column, {@code condition_acces} arrives with several distinct mojibake encodings of the same
 * word, and {@code puissance_nominale} is sometimes stated in watts. Every one of those is handled
 * leniently on purpose — rejecting the rows would discard real charge points over a publisher's
 * encoding mistake. See ADR 0012.
 */
final class IrveCsvParser {
    private static final Logger log = LoggerFactory.getLogger(IrveCsvParser.class);

    static final String SOURCE = "IRVE";
    /** Everything in this register is French by construction; the file carries no country column. */
    private static final String COUNTRY_CODE = "FR";

    private static final String STATION_ID = "id_station_itinerance";
    private static final String STATION_ID_FALLBACK = "id_station_local";

    /**
     * Rejects the prose some publishers write where an identifier belongs.
     * <p>
     * {@code id_station_itinerance} is a machine id and never contains whitespace — verified across the
     * whole 2026-07-29 edition, where the only value that does is {@code "Non concerné"}, on 1.192 rows
     * from unrelated operators. Grouping on it merged 369 separate locations into a single station that
     * took the coordinates of whichever row was read first. {@code id_station_local} is worse: 589 rows
     * say {@code "Non renseigné"} and several more spell {@code "Non concerné"} three different ways.
     * <p>
     * Testing for whitespace rather than matching those phrases keeps the rule from being a list of
     * French wordings that the next publisher spells differently.
     */
    private static final Pattern WHITESPACE = Pattern.compile("\\s");

    /**
     * Ratings above this are watts, not kilowatts: the 2026-07-29 edition holds 769 rows of
     * {@code 7360} (32 A single phase), 90 of {@code 3680} (16 A) and a tail up to {@code 160000}.
     * <p>
     * The threshold is a judgement call with a known edge: a genuine Megawatt Charging System rating
     * of 1.200 kW would be read as 1,2 kW. There is no MCS in the French consolidation today, and
     * misreading 7.360 kW as a plausible-looking fast charger would be the worse failure, so the rule
     * stands until French truck charging shows up in the file.
     */
    private static final BigDecimal WATT_THRESHOLD = new BigDecimal("1000");
    private static final BigDecimal WATTS_PER_KW = new BigDecimal("1000");

    /** French postal codes are five digits; the last group in the line is the one, not a house number. */
    private static final Pattern POSTAL_CODE = Pattern.compile("\\b(\\d{5})\\b");
    /** {@code date_maj} is ISO, but at least one row per edition omits the zero padding. */
    private static final DateTimeFormatter DATE_MAJ = DateTimeFormatter.ofPattern("yyyy-M-d");

    private IrveCsvParser() {
    }

    /** Grouping key while merging identical plugs of one station; a record because power is nullable. */
    private record Plug(String type, BigDecimal powerKw) {
    }

    /**
     * @return a stream over the grouped stations. Unlike the BNetzA parser this is <em>not</em> lazy
     * over the file: the whole CSV is read and grouped before the first station is emitted, because
     * rows of one station are scattered across it. {@code reader} is fully consumed and closed here;
     * the stations are then handed out one at a time, so only the compact grouping state — roughly
     * 64.000 entries — is ever held, never 64.000 finished {@link SourceStation}s.
     */
    static Stream<SourceStation> parse(Reader reader) throws IOException {
        Map<String, Station> stations = new LinkedHashMap<>();
        Counters counters = new Counters();

        try (CSVParser parser = CSVFormat.DEFAULT.builder().setDelimiter(',').get().parse(reader)) {
            Iterator<CSVRecord> records = parser.iterator();
            if (!records.hasNext()) throw new IOException("The IRVE consolidation is empty");

            CsvColumns columns = CsvColumns.of(records.next());
            if (!columns.has(STATION_ID))
                throw new IOException("No '" + STATION_ID + "' column found — the IRVE schema changed");
            log.info("Parsing the IRVE consolidation, {} columns", columns.size());

            while (records.hasNext()) accumulate(records.next(), columns, stations, counters);
        }

        List<Station> usable = new ArrayList<>(stations.size());
        int restricted = 0;
        for (Station station : stations.values()) {
            if (station.publiclyAccessible) usable.add(station);
            else restricted++;
        }

        Map<String, String> owners = owners(usable, counters);

        log.info("Parsed the IRVE consolidation: {} charge points in {} stations; emitting {}, "
                        + "skipping {} without a publicly accessible charge point, {} rows without usable "
                        + "coordinates and {} rows whose id columns hold prose rather than an identifier; "
                        + "{} charge point ids are listed under several stations, {} of them kept by the "
                        + "station whose own id they extend and the rest by the first",
                counters.chargePoints, stations.size(), usable.size(), restricted,
                counters.unusableRows, counters.unidentifiedRows, counters.sharedIds, counters.sharedByConvention);
        return usable.stream().map(station -> station.toSourceStation(owners));
    }

    /**
     * Decides which station keeps an {@code id_pdc_itinerance} that several stations list.
     * <p>
     * The 2026-09-30 edition holds 20.742 such ids (20.713 under two stations, 29 under three): the
     * consolidation repeats a charge point when publishers describe it independently, and often as two
     * separate entries — "HYPER U - Rumilly" and "ABB T360 HyperU Rumilly 1" both list
     * {@code FRSWSE10001499862}. Where the stations are within 30 m the ingestion joins them into one
     * master station and nothing happens; where they are not (476 pairs, median 523 m) the second one
     * failed on {@code charge_point (source, source_charge_point_id)}, in every run — 405 stations on
     * 2026-09-30. An id can live on one charge point row only, so exactly one station may carry it.
     * <p>
     * The owner is the station whose own id the charge point id extends, which is how the French
     * scheme builds them ({@code FRMELPINT5910001} → {@code FRMELEINT591000121}); with no such
     * station, or several, the first in file order, which is stable across editions. Only stations
     * that are actually emitted compete, so a restricted station cannot take an id away from a public one.
     *
     * @return charge point id to the id of the station that keeps it
     */
    private static Map<String, String> owners(List<Station> emitted, Counters counters) {
        Map<String, String> owners = new HashMap<>();
        Map<String, Integer> holders = new HashMap<>();
        for (Station station : emitted) {
            for (ChargePoint chargePoint : station.chargePoints.values()) {
                if (chargePoint.itineranceId == null || chargePoint.plugs.isEmpty()) continue;
                holders.merge(chargePoint.itineranceId, 1, Integer::sum);
                claim(owners, chargePoint.itineranceId, station.id);
            }
        }
        holders.forEach((pdcId, count) -> {
            if (count < 2) return;
            counters.sharedIds++;
            if (extendsStationId(pdcId, owners.get(pdcId))) counters.sharedByConvention++;
        });
        return owners;
    }

    /** The first claimant holds an id until a station it extends claims it; file order decides the rest. */
    private static void claim(Map<String, String> owners, String pdcId, String stationId) {
        String current = owners.get(pdcId);
        boolean takesOver = current != null && !extendsStationId(pdcId, current) && extendsStationId(pdcId, stationId);
        if (current == null || takesOver) owners.put(pdcId, stationId);
    }

    /**
     * Whether {@code pdcId} is built from {@code stationId}. A station id is
     * {@code FR + operator + P + number} and its charge points {@code FR + operator + E + number + n}, so
     * the id counts when it starts with the station id or with its {@code E} form; some publishers
     * drop the {@code FR}, which is added back for the comparison.
     */
    static boolean extendsStationId(String pdcId, String stationId) {
        if (pdcId == null || stationId == null) return false;
        String pdc = pdcId.toUpperCase(Locale.ROOT);
        String station = stationId.toUpperCase(Locale.ROOT);
        if (!station.startsWith("FR")) station = "FR" + station;
        if (pdc.startsWith(station)) return true;
        // "FR" + a three-letter operator code, then P for a station and E for a charge point.
        return station.length() > 5 && station.charAt(5) == 'P'
                && pdc.startsWith(station.substring(0, 5) + 'E' + station.substring(6));
    }

    private static void accumulate(CSVRecord row, CsvColumns columns,
                                   Map<String, Station> stations, Counters counters) {
        String id = firstIdentifier(columns.get(row, STATION_ID), columns.get(row, STATION_ID_FALLBACK));
        if (id.isEmpty()) {
            // Neither column holds something a station can be keyed on, and inventing a key would make
            // every run create the station again rather than update it.
            counters.unidentifiedRows++;
            return;
        }

        Double latitude = coordinate(columns.get(row, "consolidated_latitude"), 90);
        Double longitude = coordinate(columns.get(row, "consolidated_longitude"), 180);
        // 24 rows of the 2026-07-29 edition sit at exactly (0, 0) — the Gulf of Guinea, i.e. a missing
        // coordinate that was written as a number rather than left blank.
        if (latitude == null || longitude == null || (latitude == 0 && longitude == 0)) {
            counters.unusableRows++;
            log.debug("Skipped IRVE charge point of station {} without usable coordinates", id);
            return;
        }
        counters.chargePoints++;

        Station station = stations.computeIfAbsent(id, Station::new);
        station.merge(row, columns, latitude, longitude);
    }

    /**
     * One station, accumulated across however many charge-point rows name it.
     * <p>
     * Descriptive fields disagree between rows of the same station often enough to need a rule — 6,2 %
     * of stations state more than one operator, 4,2 % more than one name — and first-non-blank-wins is
     * chosen because file order is stable across editions, so the same station keeps the same label
     * from run to run rather than flickering.
     */
    private static final class Station {
        private final String id;
        private String name = "";
        private String street = "";
        private String city = "";
        private String postalCode = "";
        private String operator = "";
        private double latitude;
        private double longitude;
        private boolean located;
        private Instant lastUpdatedAt;
        /** Set by the first charge point that anyone may use; see {@link #restricted}. */
        private boolean publiclyAccessible;
        /**
         * One entry per {@code id_pdc_itinerance}, in file order. Keyed rather than appended because
         * the consolidation repeats a charge point when several publishers describe it, and a repeat
         * must merge into the existing entry instead of doubling the station's plug count.
         */
        private final Map<String, ChargePoint> chargePoints = new LinkedHashMap<>();

        private Station(String id) {
            this.id = id;
        }

        private void merge(CSVRecord row, CsvColumns columns, double latitude, double longitude) {
            if (!located) {
                this.latitude = latitude;
                this.longitude = longitude;
                this.located = true;
            }

            String operatorName = firstNonBlank(columns.get(row, "nom_operateur"),
                    columns.get(row, "nom_amenageur"));
            operator = keepFirst(operator, operatorName);
            // nom_station is always filled, but is sometimes an internal code; the retail brand is the
            // better map label when it is there, and the operator is a better one than nothing.
            name = keepFirst(name, firstNonBlank(columns.get(row, "nom_station"),
                    columns.get(row, "nom_enseigne"), operatorName));

            Address address = Address.of(columns.get(row, "adresse_station"),
                    columns.get(row, "consolidated_code_postal"),
                    columns.get(row, "consolidated_commune"));
            street = keepFirst(street, address.street());
            postalCode = keepFirst(postalCode, address.postalCode());
            city = keepFirst(city, address.city());

            Instant updated = lastUpdated(columns.get(row, "date_maj"), columns.get(row, "last_modified"));
            if (updated != null && (lastUpdatedAt == null || updated.isAfter(lastUpdatedAt))) lastUpdatedAt = updated;

            // A station counts as usable as soon as one of its charge points is open to everyone. 726
            // stations mix both kinds; dropping those would remove chargers a driver can genuinely use.
            if (!restricted(columns.get(row, "condition_acces"), id)) publiclyAccessible = true;

            mergeConnectors(row, columns);
        }

        /**
         * Adds this row's plugs to the charge point the row describes.
         * <p>
         * Each row <em>is</em> one charge point, identified by {@code id_pdc_itinerance} — which is
         * also the key the French <em>dynamic</em> IRVE schema publishes status against, and therefore
         * the join live availability needs (ADR 0015). It used to be dropped, and the plugs of all of
         * a station's charge points were merged into station-level totals; keeping the charge point
         * preserves both the count and the identifier without changing what the API serves, since the
         * totals are now derived when the station is read.
         */
        private void mergeConnectors(CSVRecord row, CsvColumns columns) {
            ChargePoint chargePoint = chargePointOf(row, columns);
            BigDecimal power = powerKw(columns.get(row, "puissance_nominale"));
            // The column already states the standard, so the canonical type is taken directly rather
            // than round-tripped through ConnectorTypes.normalize(), which parses free-text labels.
            // "EF" is the French designation for the domestic type E/F socket, i.e. Schuko.
            add(chargePoint, row, columns, "prise_type_2", ConnectorTypes.TYPE_2, power);
            add(chargePoint, row, columns, "prise_type_combo_ccs", ConnectorTypes.CCS, power);
            add(chargePoint, row, columns, "prise_type_chademo", ConnectorTypes.CHADEMO, power);
            add(chargePoint, row, columns, "prise_type_ef", ConnectorTypes.SCHUKO, power);
            // prise_type_autre is deliberately unmapped: it says a plug exists but not which, and a
            // connector nobody can filter for is worse than an honest gap.
        }

        /**
         * The charge point this row belongs to, created on first sight.
         * <p>
         * {@code id_pdc_itinerance} is rejected by the same whitespace rule the station id uses: the
         * publishers who write {@code "Non concerné"} where a station identifier belongs do it for
         * charge points too, and grouping on that phrase would fuse unrelated charge points into one.
         * A row without a usable id still becomes a charge point, keyed by its position, so its plugs
         * are not lost — it simply cannot take part in the live join.
         */
        private ChargePoint chargePointOf(CSVRecord row, CsvColumns columns) {
            String pdcId = columns.get(row, "id_pdc_itinerance");
            boolean usable = !pdcId.isEmpty() && !WHITESPACE.matcher(pdcId).find();
            String key = usable ? pdcId : id + "*" + (chargePoints.size() + 1);
            return chargePoints.computeIfAbsent(key,
                    k -> new ChargePoint(k, usable ? pdcId : null));
        }

        private void add(ChargePoint chargePoint, CSVRecord row, CsvColumns columns, String column, String type, BigDecimal power) {
            if (flag(columns.get(row, column))) chargePoint.plugs.merge(new Plug(type, power), 1, Integer::sum);
        }

        private SourceStation toSourceStation(Map<String, String> owners) {
            List<SourceStation.SourceChargePoint> mapped = new ArrayList<>(chargePoints.size());
            for (ChargePoint chargePoint : chargePoints.values()) {
                if (chargePoint.plugs.isEmpty()) continue;
                boolean owned = chargePoint.itineranceId == null || id.equals(owners.get(chargePoint.itineranceId));
                mapped.add(owned ? chargePoint.toSourceChargePoint() : chargePoint.toUnnamedSourceChargePoint(id));
            }

            return new SourceStation(SOURCE, id, name, street, city, postalCode, COUNTRY_CODE, operator,
                    latitude, longitude,
                    // The consolidated schema publishes no operational status, and inventing one would
                    // advertise every French station as working.
                    null,
                    lastUpdatedAt,
                    // No station-level totals: every plug belongs to the charge point of its row.
                    List.of(),
                    mapped);
        }

        /**
         * Whether a charge point is closed to the general public.
         * <p>
         * Matched on the accent-folded stem rather than the exact string, because the same two values
         * arrive in several encodings: the 2026-07-29 edition holds {@code "Accès libre"} alongside
         * {@code "Accčs libre"}, {@code "AccĂ¨s libre"} and two more mojibake variants, all produced by
         * publishers writing Latin-1 into a UTF-8 file. Folding {@code é} away and looking for the stem
         * survives that; an equality check against {@code "Accès réservé"} would silently classify a
         * mangled row as public.
         * <p>
         * A value matching neither is treated as public and logged: it is not evidence of a restriction,
         * and dropping stations over an unrecognised label would be the larger mistake.
         */
        private static boolean restricted(String conditionAcces, String stationId) {
            if (conditionAcces.isEmpty()) return false;
            String folded = fold(conditionAcces);
            if (folded.contains("serv")) return true;      // "réservé", and its mojibake spellings
            if (folded.contains("libre")) return false;
            log.debug("Unmapped IRVE condition_acces '{}' on station {}, treated as public",
                    conditionAcces, stationId);
            return false;
        }

        /**
         * The consolidation mixes {@code true/True/TRUE} with {@code 1}, and their false counterparts,
         * inside the same column — every one of the five connector columns carries all eight spellings.
         */
        private static boolean flag(String value) {
            return value.equalsIgnoreCase("true") || value.equals("1");
        }

        /**
         * Reads a rating in kW, correcting the rows that state watts.
         *
         * @return {@code null} for a blank, unreadable or non-positive rating — 5.273 rows carry
         * {@code 0}, which is an absent value rather than a charge point that delivers nothing
         */
        private static BigDecimal powerKw(String value) {
            BigDecimal parsed = decimal(value);
            if (parsed == null || parsed.signum() <= 0) return null;

            BigDecimal kw = parsed.compareTo(WATT_THRESHOLD) <= 0
                    ? parsed
                    : parsed.divide(WATTS_PER_KW, 3, RoundingMode.HALF_UP);
            return normalizeScale(kw);
        }

        /**
         * Prefers {@code date_maj}, the operator-declared update date the publication order makes the
         * pivot column, over {@code last_modified}, which only says when the consolidation last rewrote
         * the row. Falling back to the run's own clock would make every French station look freshly
         * updated and win every merge tie it should not.
         */
        private static Instant lastUpdated(String dateMaj, String lastModified) {
            if (!dateMaj.isEmpty()) {
                try {
                    return LocalDate.parse(dateMaj, DATE_MAJ).atStartOfDay(ZoneOffset.UTC).toInstant();
                } catch (DateTimeParseException _) {
                    log.debug("Unreadable IRVE date_maj '{}'", dateMaj);
                }
            }
            if (!lastModified.isEmpty()) {
                try {
                    return OffsetDateTime.parse(lastModified).toInstant();
                } catch (DateTimeParseException _) {
                    log.debug("Unreadable IRVE last_modified '{}'", lastModified);
                }
            }
            return null;
        }

        private static String keepFirst(String current, String candidate) {
            return current.isEmpty() ? candidate : current;
        }

        /** Lower-cases and strips diacritics, so {@code "Accès réservé"} becomes {@code "acces reserve"}. */
        private static String fold(String value) {
            String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
            return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        }

        /**
         * Reduces a rating to one representation per value, so {@code 22}, {@code 22.0} and {@code 22.00}
         * are one connector rather than three.
         * <p>
         * All three spellings occur in the file, and {@link BigDecimal#equals} compares the scale as well
         * as the value — so as the map key that merges plugs into quantities, they were three distinct
         * keys. One real station came out with {@code Type 2 @ 22 ×213}, {@code Type 2 @ 22.00 ×6} and
         * {@code Type 2 @ 22.0 ×1} side by side.
         */
        private static BigDecimal normalizeScale(BigDecimal value) {
            BigDecimal stripped = value.stripTrailingZeros();
            // stripTrailingZeros turns 22000 into 2.2E+4; a negative scale is correct but stores oddly.
            return stripped.scale() < 0 ? stripped.setScale(0, RoundingMode.UNNECESSARY) : stripped;
        }
    }

    /** One {@code point de charge}; the plugs accumulate as the station's rows are read. */
    private static final class ChargePoint {
        private final String key;
        private final String itineranceId;
        private final Map<Plug, Integer> plugs = new LinkedHashMap<>();

        private ChargePoint(String key, String itineranceId) {
            this.key = key;
            this.itineranceId = itineranceId;
        }

        private SourceStation.SourceChargePoint toSourceChargePoint() {
            List<SourceStation.SourceConnector> connectors = new ArrayList<>(plugs.size());
            plugs.forEach((plug, quantity) ->
                    connectors.add(new SourceStation.SourceConnector(plug.type(), plug.powerKw(), quantity)));
            return new SourceStation.SourceChargePoint(key, itineranceId, connectors);
        }

        /**
         * The same charge point on a station that does not own its id: the plugs stay, so the station
         * shows what the publisher says is there, but the id — the ingestion's unique key and the live
         * availability join — belongs to the owner. The key is derived from the id, so it is stable
         * across runs and cannot collide with the positional keys of rows that had no id.
         */
        private SourceStation.SourceChargePoint toUnnamedSourceChargePoint(String stationId) {
            List<SourceStation.SourceConnector> connectors = new ArrayList<>(plugs.size());
            plugs.forEach((plug, quantity) ->
                    connectors.add(new SourceStation.SourceConnector(plug.type(), plug.powerKw(), quantity)));
            return new SourceStation.SourceChargePoint(stationId + "*shared*" + key, null, connectors);
        }
    }

    /** Running totals, kept for the one summary line rather than logged per row. */
    private static final class Counters {
        private int chargePoints;
        private int unusableRows;
        private int unidentifiedRows;
        private int sharedIds;
        private int sharedByConvention;
    }

    /**
     * Splits {@code adresse_station} — a full one-line address such as
     * {@code "93 route de Bitche, 67506 Haguenau Cedex"} — into its parts.
     * <p>
     * Needed because the consolidation's own {@code consolidated_code_postal} and
     * {@code consolidated_commune} are filled for only 58 % and 65 % of rows, while the postal code can
     * be recovered from the address line for 97 %. The consolidated columns still win where present:
     * they are validated against the INSEE commune register, the address line is free text.
     */
    private record Address(String street, String postalCode, String city) {
        private static Address of(String line, String consolidatedPostalCode, String consolidatedCity) {
            if (line.isEmpty()) return new Address("", consolidatedPostalCode, consolidatedCity);

            Matcher matcher = POSTAL_CODE.matcher(line);
            int start = -1;
            int end = -1;
            // The last five-digit group, not the first: a house number can be five digits, a postal
            // code in a French address line cannot precede the street.
            while (matcher.find()) {
                start = matcher.start();
                end = matcher.end();
            }
            if (start < 0) return new Address(line, consolidatedPostalCode, consolidatedCity);

            String street = trimSeparators(line.substring(0, start));
            return new Address(street.isEmpty() ? line : street,
                    firstNonBlank(consolidatedPostalCode, line.substring(start, end)),
                    firstNonBlank(consolidatedCity, trimSeparators(line.substring(end))));
        }

        /**
         * Strips whitespace, commas, semicolons and dashes from both ends. A character scan rather than
         * an anchored {@code [\\s,;-]+$} regex, which backtracks quadratically on a long run of
         * separators that is not at the end (java:S8786) — and this runs on every row of a free-text
         * column.
         */
        private static String trimSeparators(String value) {
            int start = 0;
            int end = value.length();
            while (start < end && isSeparator(value.charAt(start))) start++;
            while (end > start && isSeparator(value.charAt(end - 1))) end--;
            return value.substring(start, end);
        }

        private static boolean isSeparator(char c) {
            return Character.isWhitespace(c) || c == ',' || c == ';' || c == '-';
        }
    }

    private static Double coordinate(String value, double limit) {
        BigDecimal parsed = decimal(value);
        if (parsed == null) return null;
        double degrees = parsed.doubleValue();
        return Math.abs(degrees) > limit ? null : degrees;
    }

    /** Dot-separated in this file, but a comma costs nothing to accept and appears in French exports. */
    private static BigDecimal decimal(String value) {
        String normalized = value.trim().replace(',', '.');
        if (normalized.isEmpty()) return null;
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException _) {
            return null;
        }
    }

    /** @return the first candidate that is actually an identifier, or {@code ""} if none is */
    private static String firstIdentifier(String... candidates) {
        for (String candidate : candidates)
            if (!candidate.isEmpty() && !WHITESPACE.matcher(candidate).find()) return candidate;
        return "";
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) if (candidate != null && !candidate.isEmpty()) return candidate;
        return "";
    }
}
