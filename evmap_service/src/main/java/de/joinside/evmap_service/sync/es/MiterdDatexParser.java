package de.joinside.evmap_service.sync.es;

import de.joinside.evmap_service.sync.ConnectorTypes;
import de.joinside.evmap_service.sync.EvseIds;
import de.joinside.evmap_service.sync.SourceStation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns the Spanish charging register — the MITERD publication on the DGT's National Access Point, in
 * DATEX II v3 — into {@link SourceStation}s.
 * <p>
 * Split out of the adapter so the mapping is testable against a fixture rather than the network. The
 * published shape (verified against the 2026-10-01 edition, 83 MB, 12.037 sites, 35.546 charge points,
 * 156 operators):
 * <ul>
 *   <li><strong>One site per operator, not per place.</strong> 4.363 pairs of sites lie within 35 m of
 *       each other, up to 22 on one point. The ingestion takes 30 m for "the same place" and then
 *       replaces that station's charge points, so two sites of one source inside that distance would
 *       overwrite each other on every run. The parser therefore bundles sites by position, across
 *       operators, in a way the ingestion's 30 m match can never undo: see
 *       {@link #CLUSTER_RADIUS_METRES}. The operator that owns most of the charge points names the
 *       station; the others are not shown, because {@code master.charge_point} has no operator.</li>
 *   <li><strong>The EVSE-ID is the charge point's {@code fac:name}</strong>, not its {@code id}, and 449 of
 *       the 35.546 names are no EVSE-ID at all ({@code ES*INC*E JAUME I - RRCC}). Only the shape
 *       {@code ES*XXX*E…} is kept; the others keep their plug without an id.</li>
 *   <li>Street and municipality are address lines prefixed with their label ({@code Dirección: …}),
 *       and a postcode loses its leading zero on every site north of Madrid's province numbers.</li>
 *   <li>No access restriction and no operational status are published, so nothing is skipped for access
 *       and {@code availabilityStatus} is {@code null}. Live status is a different feed and never
 *       enters {@code master.*} (ADR 0015).</li>
 * </ul>
 * The 83 MB document is read with a streaming parser: a DOM of it would need several times that in heap.
 * See ADR 0012, "Spain (L4)".
 */
final class MiterdDatexParser {
    private static final Logger log = LoggerFactory.getLogger(MiterdDatexParser.class);

    static final String SOURCE = "MITERD";
    private static final String COUNTRY = "ES";

    /**
     * Rough envelope of Spain with the Balearic and Canary Islands. Deliberately generous — it exists
     * to catch a {@code 0, 0} placeholder, not to trace the border.
     */
    private static final double MIN_LATITUDE = 27.0;
    private static final double MAX_LATITUDE = 44.5;
    private static final double MIN_LONGITUDE = -19.0;
    private static final double MAX_LONGITUDE = 5.0;

    /**
     * Sites closer than this to a bundle's first site join that bundle.
     * <p>
     * The ingestion matches a record to a known station within 30 m and then <em>replaces</em> that
     * station's charge points. Two stations of one source closer than that would each replace the
     * other's inventory, leaving only the last one's charge points. Bundling at a radius above the
     * ingestion's guarantees that the stations this parser emits are never within 30 m of each other.
     */
    static final double CLUSTER_RADIUS_METRES = 35;

    /** Cell edge for the neighbour lookup, in degrees (≈ 111 m north-south): larger than the radius. */
    private static final double GRID_DEGREES = 0.001;
    private static final double METRES_PER_DEGREE = 111_320;

    /** The register's lowest rating is 60 W on six connectors, which is not a charger. */
    private static final BigDecimal MIN_POWER_KW = BigDecimal.ONE;

    /** {@code ES*<operator>*E<id>}; the register also writes {@code ES*CAS*P3} and free text with spaces. */
    private static final Pattern EVSE_ID = Pattern.compile("ES\\*[A-Z0-9]{3}\\*E[A-Z0-9*_-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOMESTIC_SOCKET = Pattern.compile("domestic([A-Z])");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");

    private static final Map<String, String> CONNECTOR_TYPES = Map.of(
            "iec62196T2", ConnectorTypes.TYPE_2,
            "iec62196T2COMBO", ConnectorTypes.CCS,
            "iec62196T1COMBO", ConnectorTypes.CCS,
            "iec62196T1", ConnectorTypes.TYPE_1,
            "chademo", ConnectorTypes.CHADEMO,
            "domesticF", ConnectorTypes.SCHUKO,
            "iec62196T3A", "Type 3A",
            "iec62196T3C", "Type 3C");

    private MiterdDatexParser() {
    }

    private static final class RawConnector {
        String type;
        String watts;
    }

    private static final class RawPoint {
        final String id;
        String evseName;
        final List<RawConnector> connectors = new ArrayList<>();

        RawPoint(String id) {
            this.id = id;
        }
    }

    private static final class RawSite {
        final String id;
        String name;
        String updated;
        String street;
        String city;
        String postalCode;
        String operator;
        double latitude = Double.NaN;
        double longitude = Double.NaN;
        final List<RawPoint> points = new ArrayList<>();

        RawSite(String id) {
            this.id = id;
        }
    }

    /** A group of sites within the bundling radius of its first one, the anchor. */
    private static final class Bundle {
        final RawSite anchor;
        final List<RawSite> sites = new ArrayList<>();

        Bundle(RawSite anchor) {
            this.anchor = anchor;
        }
    }

    private static final class Counters {
        int sites;
        int unidentified;
        int outsideCountry;
        int withoutChargePoints;
        int bundled;
        int pointsWithoutId;
        int repeatedPointIds;
        int notEvseIds;
        int repeatedEvseIds;
        int implausiblePower;
    }

    /**
     * @param fetchedAt stamped on a station whose sites carry no usable modification time
     * @return a stream over the bundled stations. The document is read fully first, because bundling
     * needs every site, then stations are handed out one at a time.
     */
    static Stream<SourceStation> parse(Reader reader, Instant fetchedAt) throws IOException {
        List<RawSite> sites = read(reader);
        if (sites.isEmpty())
            throw new IOException("No 'energyInfrastructureSite' found in the Spanish register — the format changed");

        Counters counters = new Counters();
        counters.sites = sites.size();
        List<Bundle> bundles = bundle(screen(sites, counters), counters);
        List<SourceStation> stations = toStations(bundles, fetchedAt, counters);

        log.info("Parsed the Spanish register: {} sites; emitting {} stations from {} charge points, skipping "
                        + "{} sites without an id, {} outside Spain and {} without charge points; {} stations bundle "
                        + "several sites; {} charge points without an id, {} with a repeated id, {} EVSE names that "
                        + "are no EVSE-ID, {} repeated EVSE-IDs, {} ratings below {} kW read as unknown",
                counters.sites, stations.size(), stations.stream().mapToInt(s -> s.chargePoints().size()).sum(),
                counters.unidentified, counters.outsideCountry, counters.withoutChargePoints, counters.bundled,
                counters.pointsWithoutId, counters.repeatedPointIds, counters.notEvseIds, counters.repeatedEvseIds,
                counters.implausiblePower, MIN_POWER_KW);
        return stations.stream();
    }

    // ── reading ────────────────────────────────────────────────────────────────────────────────

    private static XMLInputFactory factory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        // The document comes from the network. A DTD would only ever be an attack: DATEX II needs none.
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }

    /**
     * Walks the document once, keeping the path of element names so that a leaf is recognised by where
     * it sits ({@code site/operator/name/values/value}) and not by its name alone: {@code name} appears on
     * the site, the operator and every charge point.
     */
    private static List<RawSite> read(Reader reader) throws IOException {
        List<RawSite> sites = new ArrayList<>();
        List<String> path = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        RawSite site = null;
        RawPoint point = null;
        RawConnector connector = null;

        XMLStreamReader xml = null;
        try {
            xml = factory().createXMLStreamReader(reader);
            while (xml.hasNext()) {
                switch (xml.next()) {
                    case XMLStreamReader.START_ELEMENT -> {
                        String name = xml.getLocalName();
                        path.add(name);
                        text.setLength(0);
                        switch (name) {
                            case "energyInfrastructureSite" -> site = new RawSite(blankToNull(xml.getAttributeValue(null, "id")));
                            case "refillPoint" -> point = site == null ? null : new RawPoint(blankToNull(xml.getAttributeValue(null, "id")));
                            case "connector" -> connector = point == null ? null : new RawConnector();
                            default -> {
                                // Everything else is read when it ends, from its text.
                            }
                        }
                    }
                    case XMLStreamReader.CHARACTERS, XMLStreamReader.CDATA -> text.append(xml.getText());
                    case XMLStreamReader.END_ELEMENT -> {
                        String name = path.get(path.size() - 1);
                        switch (name) {
                            case "connector" -> {
                                if (point != null && connector != null) point.connectors.add(connector);
                                connector = null;
                            }
                            case "refillPoint" -> {
                                if (site != null && point != null) site.points.add(point);
                                point = null;
                            }
                            case "energyInfrastructureSite" -> {
                                if (site != null) sites.add(site);
                                site = null;
                            }
                            default -> leaf(path, blankToNull(text.toString()), site, point, connector);
                        }
                        path.remove(path.size() - 1);
                        text.setLength(0);
                    }
                    default -> {
                        // Comments, processing instructions and whitespace carry nothing.
                    }
                }
            }
        } catch (XMLStreamException exception) {
            throw new IOException("Cannot read the Spanish register as XML: " + exception.getMessage(), exception);
        } finally {
            closeQuietly(xml);
        }
        return sites;
    }

    private static void leaf(List<String> path, String value, RawSite site, RawPoint point, RawConnector connector) {
        if (value == null || site == null) return;

        if (endsWith(path, "refillPoint", "name", "values", "value")) {
            if (point != null && point.evseName == null) point.evseName = value;
        } else if (endsWith(path, "connector", "connectorType")) {
            if (connector != null) connector.type = value;
        } else if (endsWith(path, "connector", "maxPowerAtSocket")) {
            if (connector != null) connector.watts = value;
        } else if (point != null) {
            // Inside a charge point nothing else is read: its other fields are voltage, current, mode.
            return;
        } else if (endsWith(path, "energyInfrastructureSite", "name", "values", "value")) {
            if (site.name == null) site.name = value;
        } else if (endsWith(path, "energyInfrastructureSite", "lastUpdated")) {
            site.updated = value;
        } else if (endsWith(path, "energyInfrastructureSite", "operator", "name", "values", "value")) {
            if (site.operator == null) site.operator = value;
        } else if (endsWith(path, "coordinatesForDisplay", "latitude")) {
            site.latitude = number(value);
        } else if (endsWith(path, "coordinatesForDisplay", "longitude")) {
            site.longitude = number(value);
        } else if (endsWith(path, "address", "postcode")) {
            site.postalCode = postalCode(value);
        } else if (endsWith(path, "addressLine", "text", "values", "value")) {
            addressLine(site, value);
        }
    }

    private static boolean endsWith(List<String> path, String... tail) {
        if (path.size() < tail.length) return false;
        int offset = path.size() - tail.length;
        for (int i = 0; i < tail.length; i++) {
            if (!path.get(offset + i).equals(tail[i])) return false;
        }
        return true;
    }

    private static double number(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException _) {
            return Double.NaN;
        }
    }

    /**
     * Spanish postcodes have five digits and begin with the province number, 01 to 52. The register
     * stores them as numbers, so every province below 10 arrives with four digits: {@code 7011} is Palma,
     * {@code 8001} is Barcelona.
     */
    private static String postalCode(String value) {
        return value.matches("\\d{4}") ? "0" + value : value;
    }

    /**
     * {@code "Dirección: Camí dels Reis 166"}, {@code "Municipio: Palma"}. The label is compared without
     * accents and in lower case, so a publisher dropping the accent does not lose the address.
     */
    private static void addressLine(RawSite site, String line) {
        int colon = line.indexOf(':');
        if (colon < 0) return;
        String label = DIACRITICS.matcher(Normalizer.normalize(line.substring(0, colon), Normalizer.Form.NFD))
                .replaceAll("").trim().toLowerCase(Locale.ROOT);
        String value = blankToNull(line.substring(colon + 1));
        if (value == null) return;
        if (label.equals("direccion") && site.street == null) site.street = value;
        else if (label.equals("municipio") && site.city == null) site.city = value;
    }

    private static void closeQuietly(XMLStreamReader xml) {
        if (xml == null) return;
        try {
            xml.close();
        } catch (XMLStreamException exception) {
            log.debug("Could not close the XML reader: {}", exception.getMessage());
        }
    }

    // ── screening and bundling ─────────────────────────────────────────────────────────────────

    private static List<RawSite> screen(List<RawSite> sites, Counters counters) {
        List<RawSite> kept = new ArrayList<>(sites.size());
        for (RawSite site : sites) {
            if (site.id == null) {
                counters.unidentified++;
            } else if (!inside(site.latitude, site.longitude)) {
                counters.outsideCountry++;
            } else if (site.points.isEmpty()) {
                counters.withoutChargePoints++;
            } else {
                kept.add(site);
            }
        }
        return kept;
    }

    private static boolean inside(double latitude, double longitude) {
        return latitude >= MIN_LATITUDE && latitude <= MAX_LATITUDE
                && longitude >= MIN_LONGITUDE && longitude <= MAX_LONGITUDE;
    }

    /**
     * Greedy bundling in a fixed order — south to north, then west to east, then by site id — so the
     * same file always yields the same stations. The first site of a bundle is its anchor; the station's
     * coordinates and its {@code sourceStationId} come from it.
     */
    private static List<Bundle> bundle(List<RawSite> sites, Counters counters) {
        List<RawSite> ordered = new ArrayList<>(sites);
        ordered.sort(Comparator.comparingDouble((RawSite site) -> site.latitude)
                .thenComparingDouble(site -> site.longitude)
                .thenComparing(site -> site.id));

        List<Bundle> bundles = new ArrayList<>();
        Map<Long, List<Bundle>> grid = new HashMap<>();

        for (RawSite site : ordered) {
            Bundle bundle = nearestBundle(grid, site);
            if (bundle == null) {
                bundle = new Bundle(site);
                bundles.add(bundle);
                grid.computeIfAbsent(cell(site.latitude, site.longitude), key -> new ArrayList<>()).add(bundle);
            }
            bundle.sites.add(site);
        }
        for (Bundle bundle : bundles) if (bundle.sites.size() > 1) counters.bundled++;
        return bundles;
    }

    private static Bundle nearestBundle(Map<Long, List<Bundle>> grid, RawSite site) {
        long row = cellIndex(site.latitude);
        long column = cellIndex(site.longitude);
        Bundle best = null;
        double bestDistance = CLUSTER_RADIUS_METRES;
        for (long dRow = -1; dRow <= 1; dRow++) {
            for (long dColumn = -1; dColumn <= 1; dColumn++) {
                for (Bundle bundle : grid.getOrDefault(key(row + dRow, column + dColumn), List.of())) {
                    double distance = metres(bundle.anchor.latitude, bundle.anchor.longitude, site.latitude, site.longitude);
                    if (distance <= bestDistance) {
                        best = bundle;
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private static long cellIndex(double degrees) {
        return (long) Math.floor(degrees / GRID_DEGREES);
    }

    private static long key(long row, long column) {
        return row * 1_000_003L + column;
    }

    private static long cell(double latitude, double longitude) {
        return key(cellIndex(latitude), cellIndex(longitude));
    }

    /** Equirectangular distance; ample at tens of metres, and it avoids trigonometry per pair. */
    private static double metres(double latitudeA, double longitudeA, double latitudeB, double longitudeB) {
        double dNorth = (latitudeB - latitudeA) * METRES_PER_DEGREE;
        double dEast = (longitudeB - longitudeA) * METRES_PER_DEGREE
                * Math.cos(Math.toRadians((latitudeA + latitudeB) / 2));
        return Math.hypot(dNorth, dEast);
    }

    // ── stations ───────────────────────────────────────────────────────────────────────────────

    private static List<SourceStation> toStations(List<Bundle> bundles, Instant fetchedAt, Counters counters) {
        // Both ids are unique per source in the database, and a repeat fails the second station in every
        // run (ADR 0012, the IRVE case). The register has none today; the first one in file order keeps it.
        Set<String> seenPointIds = new HashSet<>();
        Set<String> seenEvseIds = new HashSet<>();

        List<SourceStation> stations = new ArrayList<>(bundles.size());
        for (Bundle bundle : bundles) {
            List<SourceStation.SourceChargePoint> chargePoints = new ArrayList<>();
            Map<String, Integer> operators = new LinkedHashMap<>();
            for (RawSite site : bundle.sites) {
                for (RawPoint point : site.points) {
                    SourceStation.SourceChargePoint chargePoint = chargePoint(point, seenPointIds, seenEvseIds, counters);
                    if (chargePoint == null) continue;
                    chargePoints.add(chargePoint);
                    if (site.operator != null) operators.merge(site.operator, 1, Integer::sum);
                }
            }
            if (chargePoints.isEmpty()) {
                counters.withoutChargePoints++;
                continue;
            }
            stations.add(station(bundle, chargePoints, mostFrequent(operators), fetchedAt));
        }
        return stations;
    }

    private static SourceStation station(Bundle bundle, List<SourceStation.SourceChargePoint> chargePoints,
                                         String operator, Instant fetchedAt) {
        RawSite anchor = bundle.anchor;
        String street = firstPresent(bundle, site -> site.street);
        String name = firstPresent(firstPresent(bundle, site -> site.name), street, operator);
        return new SourceStation(SOURCE, anchor.id, name, street, firstPresent(bundle, site -> site.city),
                firstPresent(bundle, site -> site.postalCode), COUNTRY, operator, anchor.latitude, anchor.longitude,
                null, latestUpdate(bundle, fetchedAt), List.of(), chargePoints);
    }

    /** The first value any site of the bundle has, anchor first. */
    private static String firstPresent(Bundle bundle, Function<RawSite, String> field) {
        for (RawSite site : bundle.sites) {
            String value = field.apply(site);
            if (value != null) return value;
        }
        return null;
    }

    private static String firstPresent(String... values) {
        for (String value : values) if (value != null) return value;
        return null;
    }

    /** The newest modification time of any site in the bundle: the station is as current as its newest part. */
    private static Instant latestUpdate(Bundle bundle, Instant fallback) {
        Instant latest = null;
        for (RawSite site : bundle.sites) {
            if (site.updated == null) continue;
            try {
                Instant updated = OffsetDateTime.parse(site.updated).toInstant();
                if (latest == null || updated.isAfter(latest)) latest = updated;
            } catch (DateTimeParseException _) {
                // A time that cannot be read is a time that is not known.
            }
        }
        return latest == null ? fallback : latest;
    }

    private static SourceStation.SourceChargePoint chargePoint(RawPoint point, Set<String> seenPointIds,
                                                                Set<String> seenEvseIds, Counters counters) {
        if (point.id == null) {
            counters.pointsWithoutId++;
            return null;
        }
        if (!seenPointIds.add(point.id)) {
            counters.repeatedPointIds++;
            return null;
        }
        return new SourceStation.SourceChargePoint(point.id, evseId(point, seenEvseIds, counters),
                connectors(point, counters));
    }

    /** @return the EVSE-ID, or {@code null} for a name that is none or one another charge point already has */
    private static String evseId(RawPoint point, Set<String> seenEvseIds, Counters counters) {
        if (point.evseName == null) return null;
        if (!EVSE_ID.matcher(point.evseName).matches()) {
            counters.notEvseIds++;
            return null;
        }
        if (!seenEvseIds.add(EvseIds.normalize(point.evseName))) {
            counters.repeatedEvseIds++;
            return null;
        }
        return point.evseName;
    }

    private record Plug(String type, BigDecimal powerKw) {
    }

    /** One connector per distinct type and rating, with the number of plugs as quantity. */
    private static List<SourceStation.SourceConnector> connectors(RawPoint point, Counters counters) {
        Map<Plug, Integer> merged = new LinkedHashMap<>();
        for (RawConnector connector : point.connectors) {
            String type = connectorType(connector.type);
            if (type == null) continue;
            merged.merge(new Plug(type, powerKw(connector.watts, counters)), 1, Integer::sum);
        }
        List<SourceStation.SourceConnector> connectors = new ArrayList<>(merged.size());
        merged.forEach((plug, quantity) ->
                connectors.add(new SourceStation.SourceConnector(plug.type(), plug.powerKw(), quantity)));
        return connectors;
    }

    /**
     * DATEX enumerations rather than labels, so they are mapped explicitly. The domestic sockets other
     * than Schuko ({@code domesticE}, {@code domesticA}, {@code domesticL}: seven in all) and the Scame
     * types get a readable label and stay unfilterable, the intended degradation.
     */
    private static String connectorType(String raw) {
        if (raw == null) return null;
        String mapped = CONNECTOR_TYPES.get(raw);
        if (mapped != null) return mapped;
        if (raw.startsWith("iec60309")) return ConnectorTypes.CEE;
        Matcher domestic = DOMESTIC_SOCKET.matcher(raw);
        if (domestic.matches()) return "Type " + domestic.group(1);
        return ConnectorTypes.normalize(raw);
    }

    /**
     * Watts to kilowatts. {@code null} for missing, unreadable and implausibly low values: advertising
     * 0,06 kW would sort the charger last in every power-ranked map query, and "unknown" is the honest
     * answer. The scale is normalised because {@code 22000.0} and {@code 22000} would otherwise be two
     * ratings under {@link BigDecimal#equals}.
     */
    private static BigDecimal powerKw(String watts, Counters counters) {
        if (watts == null) return null;
        BigDecimal kilowatts;
        try {
            kilowatts = new BigDecimal(watts.trim()).movePointLeft(3);
        } catch (NumberFormatException _) {
            return null;
        }
        if (kilowatts.compareTo(MIN_POWER_KW) < 0) {
            counters.implausiblePower++;
            return null;
        }
        BigDecimal stripped = kilowatts.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static String mostFrequent(Map<String, Integer> counts) {
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
