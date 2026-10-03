package de.joinside.evmap_service.sync.es;

import de.joinside.evmap_service.sync.ConnectorTypes;
import de.joinside.evmap_service.sync.EvseIds;
import de.joinside.evmap_service.sync.SourceStation;
import de.joinside.evmap_service.sync.support.PositionClusters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static de.joinside.evmap_service.sync.support.SourceText.blankToNull;
import static de.joinside.evmap_service.sync.support.SourceText.firstPresent;
import static de.joinside.evmap_service.sync.support.SourceText.mostFrequent;

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
 *       station; every charge point carries its own site's operator (ADR 0022).</li>
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

    /** Local names of the DATEX II elements the walk keys on. */
    private static final String SITE = "energyInfrastructureSite";
    private static final String REFILL_POINT = "refillPoint";
    private static final String CONNECTOR = "connector";
    private static final String NAME = "name";
    private static final String VALUES = "values";
    private static final String VALUE = "value";

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

    /** The register's lowest rating is 60 W on six connectors, which is not a charger. */
    private static final BigDecimal MIN_POWER_KW = BigDecimal.ONE;

    /** {@code ES*<operator>*E<id>}; the register also writes {@code ES*CAS*P3} and free text with spaces. */
    private static final Pattern EVSE_ID = Pattern.compile("ES\\*[A-Z0-9]{3}\\*E[A-Z0-9*_-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");

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
        final List<RawSite> sites;

        Bundle(RawSite anchor, List<RawSite> sites) {
            this.anchor = anchor;
            this.sites = sites;
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
            throw new IOException("No '" + SITE + "' found in the Spanish register — the format changed");

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
        Walk walk = new Walk();
        XMLStreamReader xml = null;
        try {
            xml = factory().createXMLStreamReader(reader);
            while (xml.hasNext()) {
                switch (xml.next()) {
                    case XMLStreamConstants.START_ELEMENT ->
                            walk.start(xml.getLocalName(), blankToNull(xml.getAttributeValue(null, "id")));
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> walk.text.append(xml.getText());
                    case XMLStreamConstants.END_ELEMENT -> walk.end();
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
        return walk.sites;
    }

    /** The state of one pass over the document: where it is, what it has read so far, and the open entities. */
    private static final class Walk {
        final List<RawSite> sites = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        private final List<String> path = new ArrayList<>();
        private RawSite site;
        private RawPoint point;
        private RawConnector connector;

        void start(String name, String id) {
            path.add(name);
            text.setLength(0);
            switch (name) {
                case SITE -> site = new RawSite(id);
                case REFILL_POINT -> point = site == null ? null : new RawPoint(id);
                case CONNECTOR -> connector = point == null ? null : new RawConnector();
                default -> {
                    // Everything else is read when it ends, from its text.
                }
            }
        }

        void end() {
            switch (path.get(path.size() - 1)) {
                case CONNECTOR -> {
                    if (point != null && connector != null) point.connectors.add(connector);
                    connector = null;
                }
                case REFILL_POINT -> {
                    if (site != null && point != null) site.points.add(point);
                    point = null;
                }
                case SITE -> {
                    if (site != null) sites.add(site);
                    site = null;
                }
                default -> leaf(blankToNull(text.toString()));
            }
            path.remove(path.size() - 1);
            text.setLength(0);
        }

        private void leaf(String value) {
            if (value == null || site == null) return;
            if (point != null) pointLeaf(value);
            else siteLeaf(value);
        }

        /** Inside a charge point only its name and its connectors' type and rating are read; voltage, current and mode are not. */
        private void pointLeaf(String value) {
            if (endsWith(path, REFILL_POINT, NAME, VALUES, VALUE)) {
                if (point.evseName == null) point.evseName = value;
            } else if (connector != null && endsWith(path, CONNECTOR, "connectorType")) {
                connector.type = value;
            } else if (connector != null && endsWith(path, CONNECTOR, "maxPowerAtSocket")) {
                connector.watts = value;
            }
        }

        private void siteLeaf(String value) {
            if (endsWith(path, SITE, NAME, VALUES, VALUE)) {
                if (site.name == null) site.name = value;
            } else if (endsWith(path, SITE, "lastUpdated")) {
                site.updated = value;
            } else if (endsWith(path, SITE, "operator", NAME, VALUES, VALUE)) {
                if (site.operator == null) site.operator = value;
            } else if (endsWith(path, "coordinatesForDisplay", "latitude")) {
                site.latitude = number(value);
            } else if (endsWith(path, "coordinatesForDisplay", "longitude")) {
                site.longitude = number(value);
            } else if (endsWith(path, "address", "postcode")) {
                site.postalCode = postalCode(value);
            } else if (endsWith(path, "addressLine", "text", VALUES, VALUE)) {
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
     * Bundles in a fixed order — south to north, then west to east, then by site id — so the same file
     * always yields the same stations. The first site of a bundle is its anchor; the station's
     * coordinates and its {@code sourceStationId} come from it.
     */
    private static List<Bundle> bundle(List<RawSite> sites, Counters counters) {
        List<Bundle> bundles = PositionClusters.of(sites, site -> site.latitude, site -> site.longitude,
                        Comparator.comparingDouble((RawSite site) -> site.latitude)
                                .thenComparingDouble(site -> site.longitude)
                                .thenComparing(site -> site.id),
                        CLUSTER_RADIUS_METRES).stream()
                .map(cluster -> new Bundle(cluster.anchor(), cluster.members()))
                .toList();
        for (Bundle bundle : bundles) if (bundle.sites.size() > 1) counters.bundled++;
        return bundles;
    }

    // ── stations ───────────────────────────────────────────────────────────────────────────────

    private static List<SourceStation> toStations(List<Bundle> bundles, Instant fetchedAt, Counters counters) {
        // Both ids are unique per source in the database, and a repeat fails the second station in every
        // run (ADR 0012, the IRVE case). The register has none today; the first one in file order keeps it.
        Set<String> seenPointIds = new HashSet<>();
        Set<String> seenEvseIds = new HashSet<>();

        List<SourceStation> stations = new ArrayList<>(bundles.size());
        for (Bundle bundle : bundles) {
            SourceStation station = station(bundle, seenPointIds, seenEvseIds, fetchedAt, counters);
            if (station != null) stations.add(station);
        }
        return stations;
    }

    /** @return the station of a bundle, or {@code null} when none of its charge points is usable */
    private static SourceStation station(Bundle bundle, Set<String> seenPointIds, Set<String> seenEvseIds,
                                         Instant fetchedAt, Counters counters) {
        List<SourceStation.SourceChargePoint> chargePoints = new ArrayList<>();
        Map<String, Integer> operators = new LinkedHashMap<>();
        for (RawSite site : bundle.sites) {
            for (RawPoint point : site.points) {
                SourceStation.SourceChargePoint chargePoint =
                        chargePoint(point, site.operator, seenPointIds, seenEvseIds, counters);
                if (chargePoint == null) continue;
                chargePoints.add(chargePoint);
                if (site.operator != null) operators.merge(site.operator, 1, Integer::sum);
            }
        }
        if (chargePoints.isEmpty()) {
            counters.withoutChargePoints++;
            return null;
        }

        RawSite anchor = bundle.anchor;
        String operator = mostFrequent(operators);
        String street = firstOfBundle(bundle, site -> site.street);
        String name = firstPresent(firstOfBundle(bundle, site -> site.name), street, operator);
        return new SourceStation(SOURCE, anchor.id, name, street, firstOfBundle(bundle, site -> site.city),
                firstOfBundle(bundle, site -> site.postalCode), COUNTRY, operator, anchor.latitude, anchor.longitude,
                null, latestUpdate(bundle, fetchedAt), List.of(), chargePoints);
    }

    /** The first value any site of the bundle has, anchor first. */
    private static String firstOfBundle(Bundle bundle, Function<RawSite, String> field) {
        for (RawSite site : bundle.sites) {
            String value = field.apply(site);
            if (value != null) return value;
        }
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

    private static SourceStation.SourceChargePoint chargePoint(RawPoint point, String operator, Set<String> seenPointIds,
                                                                Set<String> seenEvseIds, Counters counters) {
        if (point.id == null) {
            counters.pointsWithoutId++;
            return null;
        }
        if (!seenPointIds.add(point.id)) {
            counters.repeatedPointIds++;
            return null;
        }
        // The register has no price: ad-hoc prices are in its separate dynamic publication (ADR 0022).
        return new SourceStation.SourceChargePoint(point.id, evseId(point, seenEvseIds, counters),
                connectors(point, counters), operator, null);
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
            String type = ConnectorTypes.fromDatex(connector.type);
            if (type == null) continue;
            merged.merge(new Plug(type, powerKw(connector.watts, counters)), 1, Integer::sum);
        }
        List<SourceStation.SourceConnector> connectors = new ArrayList<>(merged.size());
        merged.forEach((plug, quantity) ->
                connectors.add(new SourceStation.SourceConnector(plug.type(), plug.powerKw(), quantity)));
        return connectors;
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
}
