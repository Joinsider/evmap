package de.joinside.evmap_service.availability.irve;

import de.joinside.evmap_service.availability.ChargePointAvailability;
import de.joinside.evmap_service.availability.LiveAvailability;
import de.joinside.evmap_service.sync.EvseIds;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the national consolidation of {@code schema-irve-dynamique} (v2.3.0) onto
 * {@link ChargePointAvailability}.
 * <p>
 * Split out of the provider so the mapping is testable against a fixture rather than the network,
 * and so each tolerance below is written down once. The file is the union of dozens of publishers'
 * own files and reads like it:
 * <ul>
 *   <li>The same {@code id_pdc_itinerance} appears more than once — 111.122 distinct ids in 121.965
 *       rows on 2026-09-28 — when a charge point is published by its operator and again by a roaming
 *       platform. The newest {@code horodatage} wins.</li>
 *   <li>{@code horodatage} arrives as {@code 2026-09-16 09:05:10.566659+00:00},
 *       {@code 2026-09-28T16:12:12+02:00} and, from older feeds, {@code 2020-12-29 14:39:41} with no
 *       offset at all. The last is read as French local time, which is what its publishers meant.</li>
 *   <li>A quarter of the rows are months old: feeds that stopped, not charge points that stayed free.
 *       Rows older than {@code maxAge} are dropped rather than reported, because the client would
 *       otherwise show a confident "free" with an age nobody reads.</li>
 * </ul>
 * No coordinates, no station: the file carries the charge point id and its state and nothing else.
 * That is enough, because the static IRVE file the sync ingests stores the same {@code
 * id_pdc_itinerance} as {@code master.charge_point.evse_id} — 90,1 % of the live ids matched when this
 * was written.
 */
final class IrveDynamicCsv {
    private static final Logger log = LoggerFactory.getLogger(IrveDynamicCsv.class);

    /** What an offset-less timestamp from a French publisher means. */
    private static final ZoneId PUBLISHER_ZONE = ZoneId.of("Europe/Paris");

    private IrveDynamicCsv() {
    }

    /**
     * @param notBefore rows observed earlier than this are dropped as stale
     * @return one entry per normalized charge point id, carrying its newest observation
     */
    static List<ChargePointAvailability> parse(Reader csv, Instant notBefore) throws IOException {
        Map<String, ChargePointAvailability> newest = HashMap.newHashMap(120_000);
        int rows = 0;
        int unusable = 0;
        int stale = 0;

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .get();
        try (CSVParser parser = format.parse(csv)) {
            for (CSVRecord row : parser) {
                rows++;
                String evseId = EvseIds.normalize(value(row, "id_pdc_itinerance"));
                Instant observedAt = timestamp(value(row, "horodatage"));
                String status = toLiveAvailability(value(row, "etat_pdc"), value(row, "occupation_pdc"));
                if (evseId == null || observedAt == null) {
                    unusable++;
                } else if (observedAt.isBefore(notBefore)) {
                    stale++;
                } else {
                    newest.merge(evseId, new ChargePointAvailability(evseId, status, observedAt),
                            (kept, candidate) -> candidate.observedAt().isAfter(kept.observedAt()) ? candidate : kept);
                }
            }
        }
        log.debug("IRVE dynamique: {} row(s), {} charge point(s) reported, {} stale, {} unusable",
                rows, newest.size(), stale, unusable);
        return List.copyOf(newest.values());
    }

    /**
     * Collapses the schema's two axes — is it working ({@code etat_pdc}), is it free
     * ({@code occupation_pdc}) — onto the client's one.
     * <p>
     * Broken wins over everything: a charge point out of service is not worth driving to whatever its
     * occupancy says. Occupied is believed whatever the service state says, because a session in
     * progress proves the charge point works. Free is only believed of a charge point also reported in
     * service — "free, state unknown" is exactly the answer that sends a driver to a dead post.
     */
    static String toLiveAvailability(String etatPdc, String occupationPdc) {
        String etat = etatPdc == null ? "" : etatPdc.toLowerCase(Locale.ROOT);
        String occupation = occupationPdc == null ? "" : occupationPdc.toLowerCase(Locale.ROOT);
        if (etat.equals("hors_service")) return LiveAvailability.OUT_OF_ORDER;
        if (occupation.equals("occupe") || occupation.equals("reserve")) return LiveAvailability.OCCUPIED;
        if (occupation.equals("libre") && etat.equals("en_service")) return LiveAvailability.AVAILABLE;
        return LiveAvailability.UNKNOWN;
    }

    /** ISO 8601 as the schema asks, plus the space-separated and offset-less spellings it gets. */
    static Instant timestamp(String horodatage) {
        if (horodatage == null || horodatage.isEmpty()) return null;
        String iso = horodatage.replace(' ', 'T');
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (DateTimeException _) {
            try {
                return LocalDateTime.parse(iso).atZone(PUBLISHER_ZONE).toInstant();
            } catch (DateTimeException _) {
                return null;
            }
        }
    }

    private static String value(CSVRecord row, String column) {
        return row.isMapped(column) && row.isSet(column) ? row.get(column) : null;
    }
}
