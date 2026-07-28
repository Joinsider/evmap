package de.joinside.evmap_service.sync.bnetza;

import de.joinside.evmap_service.sync.AvailabilityStatus;
import de.joinside.evmap_service.sync.ConnectorTypes;
import de.joinside.evmap_service.sync.SourceStation;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Turns the Bundesnetzagentur Ladesäulenregister CSV into {@link SourceStation}s.
 * <p>
 * Split out of the adapter so the mapping is testable against a fixture without downloading 53 MB.
 * The published shape (verified against the 2026-07-07 edition, 113.385 rows):
 * <ul>
 *   <li>UTF-8 with BOM, CRLF, {@code ;}-separated, RFC4180 quoting — quoted fields contain both
 *       delimiters and newlines, so this needs a real CSV reader.</li>
 *   <li>Ten preamble lines before the header, one of which carries the edition date. The preamble
 *       length is not a documented guarantee, so the header is located by its first column instead.</li>
 *   <li>One row per <em>Ladeeinrichtung</em> with up to six <em>Ladepunkte</em> in repeated column
 *       groups; a single Ladepunkt's {@code Steckertypen} cell is itself a {@code ;}-separated list.</li>
 *   <li>German decimal comma throughout ({@code 48,442398}, {@code 3,7}).</li>
 * </ul>
 */
final class BnetzaCsvParser {
    private static final Logger log = LoggerFactory.getLogger(BnetzaCsvParser.class);

    static final String SOURCE = "BNetzA";
    /** Everything in this register is German by construction — the file carries no country column. */
    private static final String COUNTRY_CODE = "DE";

    private static final String ID_COLUMN = "Ladeeinrichtungs-ID";
    private static final String LAST_UPDATE_PREFIX = "Letzte Aktualisierung vom:";
    private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final int MAX_CHARGE_POINTS = 6;
    /** Escaped rather than literal: the mark is invisible in an editor and easy to lose in a merge. */
    private static final String BOM = "\uFEFF";

    private BnetzaCsvParser() {
    }

    /** Grouping key while merging duplicate plugs; a record because the rating is nullable. */
    private record Plug(String type, BigDecimal powerKw) {
    }

    /**
     * @param fallbackUpdatedAt used when the preamble carries no readable edition date — normally the
     *                          date embedded in the published file name
     * @return a lazy stream; closing it closes {@code reader}
     */
    static Stream<SourceStation> parse(Reader reader, Instant fallbackUpdatedAt) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder().setDelimiter(';').get();
        CSVParser parser = format.parse(reader);
        Iterator<CSVRecord> records = parser.iterator();

        // The header has to be found before any row can be mapped, so the preamble is consumed eagerly.
        // It is a handful of records regardless of how large the file is.
        Instant updatedAt = fallbackUpdatedAt;
        Map<String, Integer> columns = null;
        while (records.hasNext()) {
            CSVRecord record = records.next();
            if (record.size() == 0) continue;
            String first = clean(record.get(0));
            if (first.startsWith(LAST_UPDATE_PREFIX)) {
                Instant parsed = parseEditionDate(first);
                if (parsed != null) updatedAt = parsed;
            }
            if (ID_COLUMN.equals(first)) {
                columns = index(record);
                break;
            }
        }
        if (columns == null) {
            parser.close();
            throw new IOException("No '" + ID_COLUMN + "' header row found — the register layout changed");
        }

        log.info("Parsing BNetzA register, edition {}, {} columns", updatedAt, columns.size());
        Map<String, Integer> header = columns;
        Instant edition = updatedAt;
        return StreamSupport
                .stream(Spliterators.spliteratorUnknownSize(records, Spliterator.ORDERED | Spliterator.NONNULL), false)
                .map(record -> toStation(record, header, edition))
                .filter(Objects::nonNull)
                .onClose(() -> {
                    try {
                        parser.close();
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                });
    }

    private static Map<String, Integer> index(CSVRecord header) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.size(); i++) columns.putIfAbsent(clean(header.get(i)), i);
        return columns;
    }

    private static Instant parseEditionDate(String preambleLine) {
        String value = preambleLine.substring(LAST_UPDATE_PREFIX.length()).trim();
        try {
            return LocalDate.parse(value, GERMAN_DATE).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException exception) {
            log.warn("Unreadable edition date '{}' in the register preamble", value);
            return null;
        }
    }

    private static SourceStation toStation(CSVRecord record, Map<String, Integer> columns, Instant editionDate) {
        String id = get(record, columns, ID_COLUMN);
        if (id.isEmpty()) return null;

        Double latitude = decimal(get(record, columns, "Breitengrad"));
        Double longitude = decimal(get(record, columns, "Längengrad"));
        if (latitude == null || longitude == null) {
            log.debug("Skipped charge point {} without coordinates", id);
            return null;
        }

        String operator = get(record, columns, "Betreiber");
        // "Anzeigename (Karte)" is optional and blank for 63.290 of 113.385 rows in the 2026-07-07
        // edition, so the map label falls back to the site description and finally to the operator.
        String name = firstNonBlank(get(record, columns, "Anzeigename (Karte)"),
                get(record, columns, "Standortbezeichnung"),
                operator);

        return new SourceStation(SOURCE,
                id,
                name,
                street(record, columns),
                get(record, columns, "Ort"),
                get(record, columns, "Postleitzahl"),
                COUNTRY_CODE,
                operator,
                latitude,
                longitude,
                availability(get(record, columns, "Status"), id),
                // The register has no per-row timestamp — only the edition date of the whole file.
                editionDate,
                connectors(record, columns));
    }

    /**
     * Maps the register's {@code Status} column onto the shared vocabulary.
     * <p>
     * These rows used to be dropped, which quietly deleted every station under maintenance — 21 of
     * 113.385 in the 2026-07-07 edition. They are permanently installed, registered stations that
     * happen to be out of service today, so they belong on the map with their state attached; that is
     * also the field Lastenheft §3 asks to be prepared. An unrecognised status yields {@code null}
     * rather than a guess: "we do not know" is honest, "operational" would not be.
     */
    private static String availability(String status, String id) {
        return switch (status) {
            case "In Betrieb" -> AvailabilityStatus.OPERATIONAL;
            case "In Wartung" -> AvailabilityStatus.MAINTENANCE;
            case "" -> null;
            default -> {
                log.debug("Unmapped BNetzA status '{}' on charge point {}", status, id);
                yield null;
            }
        };
    }

    private static String street(CSVRecord record, Map<String, Integer> columns) {
        String street = get(record, columns, "Straße");
        String houseNumber = get(record, columns, "Hausnummer");
        return houseNumber.isEmpty() ? street : (street + " " + houseNumber).trim();
    }

    /**
     * Flattens the six Ladepunkt column groups into connectors, merging identical (type, power) pairs
     * into a quantity — a site with four Type 2 sockets at 22 kW is one row with {@code quantity=4}
     * rather than four rows the UI would have to group again.
     */
    private static List<SourceStation.SourceConnector> connectors(CSVRecord record, Map<String, Integer> columns) {
        Map<Plug, Integer> quantities = new LinkedHashMap<>();
        for (int point = 1; point <= MAX_CHARGE_POINTS; point++) {
            String[] labels = split(get(record, columns, "Steckertypen" + point));
            if (labels.length == 0) continue;
            String[] powers = split(get(record, columns, "Nennleistung Stecker" + point));

            for (int i = 0; i < labels.length; i++) {
                String type = ConnectorTypes.normalize(labels[i]);
                if (type == null) continue;
                quantities.merge(new Plug(type, powerOf(powers, i)), 1, Integer::sum);
            }
        }

        List<SourceStation.SourceConnector> connectors = new ArrayList<>(quantities.size());
        quantities.forEach((plug, quantity) ->
                connectors.add(new SourceStation.SourceConnector(plug.type(), plug.powerKw(), quantity)));
        return connectors;
    }

    /**
     * A Ladepunkt listing two plugs also lists two ratings ({@code "22; 3,7"}), positionally aligned.
     * When the two lists disagree in length a single rating still applies to every plug of that point;
     * anything else is ambiguous and is dropped rather than guessed.
     */
    private static BigDecimal powerOf(String[] powers, int index) {
        if (powers.length == 1) return decimalOrNull(powers[0]);
        if (index < powers.length) return decimalOrNull(powers[index]);
        return null;
    }

    private static String[] split(String value) {
        if (value.isEmpty()) return new String[0];
        return value.split(";");
    }

    private static Double decimal(String value) {
        BigDecimal parsed = decimalOrNull(value);
        return parsed == null ? null : parsed.doubleValue();
    }

    private static BigDecimal decimalOrNull(String value) {
        String normalized = value.trim().replace(',', '.');
        if (normalized.isEmpty()) return null;
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) if (!candidate.isEmpty()) return candidate;
        return "";
    }

    private static String get(CSVRecord record, Map<String, Integer> columns, String column) {
        Integer index = columns.get(column);
        if (index == null || index >= record.size()) return "";
        return clean(record.get(index));
    }

    /** Trims and strips the byte-order mark the register's first field carries. */
    private static String clean(String value) {
        return value == null ? "" : value.replace(BOM, "").trim();
    }
}
