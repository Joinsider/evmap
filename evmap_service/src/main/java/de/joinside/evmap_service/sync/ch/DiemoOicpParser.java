package de.joinside.evmap_service.sync.ch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.joinside.evmap_service.sync.ConnectorTypes;
import de.joinside.evmap_service.sync.EvseIds;
import de.joinside.evmap_service.sync.SourceStation;
import de.joinside.evmap_service.sync.support.PositionClusters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static de.joinside.evmap_service.sync.support.SourceText.blankToNull;
import static de.joinside.evmap_service.sync.support.SourceText.firstPresent;
import static de.joinside.evmap_service.sync.support.SourceText.mostFrequent;

/**
 * Turns the Swiss charging register — the Federal Office of Energy's <em>ich-tanke-strom</em> / DIEMO
 * feed in OICP format — into {@link SourceStation}s.
 * <p>
 * Split out of the adapter so the mapping is testable against a fixture rather than the network. The
 * published shape (verified against the 2026-09-30 edition, 1,0 MB gzip / 27 MB JSON, 41 operators,
 * 19.478 records):
 * <ul>
 *   <li><strong>One record per EVSE, not per station.</strong> {@code ChargingStationId} yields 13.658
 *       groups but the coordinates only 8.994 sites, and up to 75 EVSEs of different station ids share
 *       a point. The parser therefore clusters by position, in a way the ingestion's 30 m match can
 *       never undo: see {@link #CLUSTER_RADIUS_METRES}.</li>
 *   <li>Every field is loosely typed: {@code PostalCode} is a string on most records and a number on
 *       eight, {@code power} arrives as int, float, string or {@code null}, and
 *       {@code ChargingStationNames} is a list, a single object or {@code null}. Nothing here trusts a
 *       type.</li>
 *   <li>The register is not clean: 168 records sit at the placeholder {@code 50.0, -15.0}, a few are
 *       real stations in Germany or Austria, and five EVSE-IDs appear under two operators.</li>
 *   <li>The separate status feed is deliberately not read. It is live occupancy and never enters
 *       {@code master.*} (ADR 0015), so {@code availabilityStatus} is {@code null}.</li>
 * </ul>
 * See ADR 0012, "Switzerland (L2)".
 */
final class DiemoOicpParser {
    private static final Logger log = LoggerFactory.getLogger(DiemoOicpParser.class);

    static final String SOURCE = "DIEMO";
    private static final String COUNTRY_CH = "CH";
    private static final String COUNTRY_LI = "LI";

    /**
     * Rough envelope of Switzerland and Liechtenstein. Deliberately generous — it exists to catch the
     * {@code 50.0, -15.0} placeholder and the stations of neighbouring countries the feed carries, not
     * to trace the border.
     */
    private static final double MIN_LATITUDE = 45.7;
    private static final double MAX_LATITUDE = 47.9;
    private static final double MIN_LONGITUDE = 5.9;
    private static final double MAX_LONGITUDE = 10.6;

    /**
     * EVSEs closer than this to a site's first EVSE join that site.
     * <p>
     * The ingestion matches a record to a known station within 30 m and then <em>replaces</em> that
     * station's charge points. Two stations of one source closer than that would each replace the
     * other's inventory, leaving only the last one's charge points. Clustering at a radius above the
     * ingestion's guarantees that the stations this parser emits are never within 30 m of each other.
     */
    static final double CLUSTER_RADIUS_METRES = 35;

    /**
     * Real EVSEs carry one to four plugs in the register; the largest ordinary count in the 2026-09-30
     * edition is four. Two swisscharge records list 157 identical Type 2 plugs on one EVSE, which is
     * not a charge point but a data error, and would put 157 sockets into the station's totals.
     */
    private static final int MAX_PLUGS_PER_EVSE = 8;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private DiemoOicpParser() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Root(@JsonProperty("EVSEData") List<Operator> operators) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Operator(@JsonProperty("OperatorName") String name,
                    @JsonProperty("EVSEDataRecord") List<Evse> evses) {
    }

    /** {@code names} and {@code power} stay {@link JsonNode}s because the feed varies their shape. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Evse(@JsonProperty("EvseID") String evseId,
                @JsonProperty("Accessibility") String accessibility,
                @JsonProperty("Address") Address address,
                @JsonProperty("GeoCoordinates") Geo geo,
                @JsonProperty("Plugs") List<String> plugs,
                @JsonProperty("ChargingFacilities") List<Facility> facilities,
                @JsonProperty("ChargingStationNames") JsonNode names) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Address(@JsonProperty("Street") String street,
                   @JsonProperty("City") String city,
                   @JsonProperty("PostalCode") String postalCode,
                   @JsonProperty("Country") String country) {
    }

    /** {@code "47.572207 8.522895"} — latitude then longitude, space separated. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Geo(@JsonProperty("Google") String google) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Facility(@JsonProperty("power") JsonNode power) {
    }

    /** An EVSE that passed every filter, with what the site needs from it. */
    private record Candidate(Evse evse, String operator, double latitude, double longitude) {
    }

    private static final class Counters {
        int records;
        int restricted;
        int outsideCountry;
        int unidentified;
        int duplicateEvse;
        int oversized;
    }

    private static final class Site {
        final double latitude;
        final double longitude;
        final Map<String, Integer> operators = new LinkedHashMap<>();
        final List<SourceStation.SourceChargePoint> chargePoints = new ArrayList<>();
        String name;
        String street;
        String city;
        String postalCode;
        String country;

        Site(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    /**
     * @param fetchedAt stamped on every station: the feed carries no modification time per record, and
     *                  is republished continuously, so "as of this download" is the honest provenance
     * @return a stream over the clustered stations. The document is read fully first, because
     * clustering needs every record, then stations are handed out one at a time.
     */
    static Stream<SourceStation> parse(Reader reader, Instant fetchedAt) throws IOException {
        Root root = MAPPER.readValue(reader, Root.class);
        if (root == null || root.operators() == null)
            throw new IOException("No 'EVSEData' found in the Swiss register — the format changed");

        Counters counters = new Counters();
        List<Candidate> candidates = collect(root, counters);
        List<Site> sites = cluster(candidates, counters);

        log.info("Parsed the Swiss register: {} EVSE records; emitting {} stations from {} EVSEs, skipping "
                        + "{} restricted or test, {} outside Switzerland and Liechtenstein, {} without an "
                        + "EVSE-ID and {} repeated EVSE-IDs; {} EVSEs with implausibly many plugs collapsed",
                counters.records, sites.size(), sites.stream().mapToInt(s -> s.chargePoints.size()).sum(),
                counters.restricted, counters.outsideCountry, counters.unidentified,
                counters.duplicateEvse, counters.oversized);

        return sites.stream().map(site -> toStation(site, fetchedAt));
    }

    private static List<Candidate> collect(Root root, Counters counters) {
        List<Candidate> candidates = new ArrayList<>();
        // The same EVSE-ID is listed by two operators five times, with conflicting access. Restricted
        // records are dropped before this check, so a public listing always beats a restricted one.
        Set<String> seenEvseIds = new HashSet<>();

        for (Operator operator : root.operators()) {
            if (operator == null || operator.evses() == null) continue;
            String operatorName = blankToNull(operator.name());
            for (Evse evse : operator.evses()) {
                Candidate candidate = screen(evse, operatorName, seenEvseIds, counters);
                if (candidate != null) candidates.add(candidate);
            }
        }
        return candidates;
    }

    /** @return the EVSE as a candidate for a station, or {@code null} after counting why it is not one */
    private static Candidate screen(Evse evse, String operatorName, Set<String> seenEvseIds, Counters counters) {
        if (evse == null) return null;
        counters.records++;

        if (isRestricted(evse.accessibility())) {
            counters.restricted++;
            return null;
        }
        String evseKey = evse.evseId() == null ? null : EvseIds.normalize(evse.evseId());
        if (evseKey == null) {
            counters.unidentified++;
            return null;
        }
        Optional<Position> position = position(evse.geo());
        if (position.isEmpty()) {
            counters.outsideCountry++;
            return null;
        }
        if (!seenEvseIds.add(evseKey)) {
            counters.duplicateEvse++;
            return null;
        }
        return new Candidate(evse, operatorName, position.get().latitude(), position.get().longitude());
    }

    /**
     * Anything the feed says is not for the public. Matched by marker rather than by comparing against
     * "publicly accessible", so that a new wording for a restriction fails closed and a new wording for
     * a public charger fails open — the second is the cheaper mistake.
     */
    private static boolean isRestricted(String accessibility) {
        if (accessibility == null) return false;
        String value = accessibility.toLowerCase(Locale.ROOT);
        return value.contains("restricted") || value.contains("private") || value.contains("test");
    }

    private record Position(double latitude, double longitude) {
    }

    /** @return the position, or empty for anything unreadable or off-country */
    private static Optional<Position> position(Geo geo) {
        if (geo == null || geo.google() == null) return Optional.empty();
        String[] parts = geo.google().trim().split("\\s+");
        if (parts.length != 2) return Optional.empty();
        try {
            double latitude = Double.parseDouble(parts[0]);
            double longitude = Double.parseDouble(parts[1]);
            boolean inside = latitude >= MIN_LATITUDE && latitude <= MAX_LATITUDE
                    && longitude >= MIN_LONGITUDE && longitude <= MAX_LONGITUDE;
            return inside ? Optional.of(new Position(latitude, longitude)) : Optional.empty();
        } catch (NumberFormatException _) {
            // The feed writes "None None" where a station has no entrance coordinate.
            return Optional.empty();
        }
    }

    /**
     * Clusters in a fixed order — south to north, then west to east, then by EVSE-ID — so the same file
     * always yields the same stations. The first EVSE of a cluster is its anchor; the station's
     * coordinates and its {@code sourceStationId} come from it.
     */
    private static List<Site> cluster(List<Candidate> candidates, Counters counters) {
        List<Site> sites = new ArrayList<>();
        for (PositionClusters.Cluster<Candidate> cluster : PositionClusters.of(candidates, Candidate::latitude,
                Candidate::longitude,
                Comparator.comparingDouble(Candidate::latitude)
                        .thenComparingDouble(Candidate::longitude)
                        .thenComparing(candidate -> candidate.evse().evseId()),
                CLUSTER_RADIUS_METRES)) {
            Site site = new Site(cluster.anchor().latitude(), cluster.anchor().longitude());
            for (Candidate candidate : cluster.members()) add(site, candidate, counters);
            sites.add(site);
        }
        return sites;
    }

    private static void add(Site site, Candidate candidate, Counters counters) {
        Evse evse = candidate.evse();
        Address address = evse.address();

        if (candidate.operator() != null) site.operators.merge(candidate.operator(), 1, Integer::sum);
        if (site.name == null) site.name = name(evse.names());
        if (address != null) {
            if (site.street == null) site.street = blankToNull(address.street());
            if (site.city == null) site.city = blankToNull(address.city());
            if (site.postalCode == null) site.postalCode = blankToNull(address.postalCode());
            if (site.country == null) site.country = country(address.country());
        }

        String evseId = evse.evseId().trim();
        site.chargePoints.add(new SourceStation.SourceChargePoint(evseId, evseId, connectors(evse, counters)));
    }

    /** Liechtenstein is the only other country the feed legitimately covers. */
    private static String country(String published) {
        if (published == null) return null;
        String value = published.trim().toUpperCase(Locale.ROOT);
        return value.equals("LI") || value.equals("LIE") ? COUNTRY_LI : COUNTRY_CH;
    }

    /**
     * The name of the first EVSE that has one, German where several languages are offered. The names
     * describe individual EVSEs and are often unhelpful ("Parkplatz 4"); the address, which is shown
     * next to them, carries the rest.
     */
    private static String name(JsonNode names) {
        if (names == null || names.isNull()) return null;
        if (names.isObject()) return blankToNull(names.path("value").asText(null));
        if (!names.isArray()) return null;
        String first = null;
        for (JsonNode entry : names) {
            String value = blankToNull(entry.path("value").asText(null));
            if (value == null) continue;
            if ("de".equalsIgnoreCase(entry.path("lang").asText(""))) return value;
            if (first == null) first = value;
        }
        return first;
    }

    private record Plug(String type, BigDecimal powerKw) {
    }

    /**
     * Pairs each plug with a rating. {@code Plugs} and {@code ChargingFacilities} are parallel lists
     * (a CHAdeMO and a CCS plug of one EVSE arrive with 50 and 300 kW in that order); where the lengths
     * differ but every facility states the same rating, that rating applies to all plugs; otherwise the
     * rating is unknown rather than guessed.
     */
    private static List<SourceStation.SourceConnector> connectors(Evse evse, Counters counters) {
        List<String> plugs = evse.plugs() == null ? List.of() : evse.plugs();
        List<BigDecimal> powers = powers(evse);
        Set<BigDecimal> distinctPowers = new HashSet<>(powers);

        Map<Plug, Integer> merged = new LinkedHashMap<>();
        for (int i = 0; i < plugs.size(); i++) {
            String type = ConnectorTypes.normalize(plugs.get(i));
            if (type != null) merged.merge(new Plug(type, powerOf(i, plugs.size(), powers, distinctPowers)), 1, Integer::sum);
        }

        boolean oversized = plugs.size() > MAX_PLUGS_PER_EVSE;
        if (oversized) counters.oversized++;

        List<SourceStation.SourceConnector> connectors = new ArrayList<>(merged.size());
        merged.forEach((plug, quantity) ->
                connectors.add(new SourceStation.SourceConnector(plug.type(), plug.powerKw(), oversized ? 1 : quantity)));
        return connectors;
    }

    /** One entry per facility, {@code null} where the rating is unknown; may be shorter or longer than the plugs. */
    private static List<BigDecimal> powers(Evse evse) {
        if (evse.facilities() == null) return List.of();
        // Stream.toList() accepts the nulls that stand for "unknown"; Collectors.toList() would too.
        return evse.facilities().stream().map(facility -> facility == null ? null : power(facility.power())).toList();
    }

    private static BigDecimal powerOf(int plug, int plugCount, List<BigDecimal> powers, Set<BigDecimal> distinctPowers) {
        if (powers.size() == plugCount) return powers.get(plug);
        return distinctPowers.size() == 1 ? powers.get(0) : null;
    }

    /**
     * {@code null} for missing, unreadable and non-positive values: 709 facilities say {@code 0} and 487
     * say nothing, and both mean "unknown" — advertising 0 kW would sort the charger last in every
     * power-ranked map query. The scale is normalised because {@code 22}, {@code 22.0} and {@code "22.0"}
     * all occur and {@link BigDecimal#equals} would treat them as three ratings.
     */
    private static BigDecimal power(JsonNode node) {
        if (node == null || node.isNull()) return null;
        BigDecimal value;
        try {
            value = node.isNumber() ? node.decimalValue() : new BigDecimal(node.asText("").trim());
        } catch (NumberFormatException _) {
            return null;
        }
        if (value.signum() <= 0) return null;
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static SourceStation toStation(Site site, Instant fetchedAt) {
        String operator = mostFrequent(site.operators);
        String name = firstPresent(site.name, site.street, operator);
        return new SourceStation(SOURCE, stationId(site), name, site.street, site.city, site.postalCode,
                site.country == null ? COUNTRY_CH : site.country, operator, site.latitude, site.longitude,
                null, fetchedAt, List.of(), site.chargePoints);
    }

    /** Anchor coordinate at five decimals (≈ 1 m): anchors are at least 35 m apart, so it is unique. */
    private static String stationId(Site site) {
        return String.format(Locale.ROOT, "%.5f,%.5f", site.latitude, site.longitude);
    }
}
