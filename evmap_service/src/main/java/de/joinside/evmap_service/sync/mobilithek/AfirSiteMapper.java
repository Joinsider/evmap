package de.joinside.evmap_service.sync.mobilithek;

import com.fasterxml.jackson.databind.JsonNode;
import de.joinside.evmap_service.mobilithek.Datex;
import de.joinside.evmap_service.sync.ConnectorTypes;
import de.joinside.evmap_service.sync.EvseIds;
import de.joinside.evmap_service.sync.SourceStation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Maps one AFIR site onto {@link SourceStation}s, one per {@code energyInfrastructureStation} — the register's
 * granularity, and the level the feeds attach the register's station id to (ADR 0025).
 * <p>
 * One schema, many dialects: the EVSE-ID is an {@code externalIdentifier} typed {@code evseId} or the refill point's
 * own id; location and address sit on the station or only on the site; {@code FacilityLocation} is also spelled
 * {@code facilityLocation}; coordinates come as {@code coordinatesForDisplay} or {@code pointCoordinates}. The
 * accessors below read every variant seen on 2026-10-03 rather than one publisher's.
 * <p>
 * Not thread-safe: one instance per run, because it remembers the EVSE-IDs already emitted. A charge point relayed by
 * a second feed (e-clearing.net carries 2.543 of ladenetz.de's and ladebusiness') is emitted once, by the feed read
 * first; a station left without charge points by that is not emitted at all.
 */
final class AfirSiteMapper {

    /** Counts what was dropped, for the one summary line per feed. */
    static final class Counters {
        int stations;
        int chargePoints;
        int foreign;
        int withoutPosition;
        int relayed;
        int withoutEvseId;
        int linked;
    }

    /** The register's station id, which three publishers type differently; EnBW's is an id despite its name. */
    private static final Set<String> REGISTER_ID_TYPES = Set.of("stationIdBNetzA", "operatorIdBNetzA");
    private static final String EVSE_ID_TYPE = "evseId";
    private static final String LOCATION_REFERENCE = "locationReference";
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    /** {@code DE*EWE}, {@code DESTA}: an operator id where a name was expected. */
    private static final Pattern OPERATOR_ID = Pattern.compile("[A-Z]{2}\\*?[A-Z0-9]{3}");
    /** A display name worth showing has a lowercase word in it; {@code 000501}, {@code ENE_SN0000325} do not. */
    private static final Pattern WORD = Pattern.compile("\\p{Ll}{3}");
    private static final BigDecimal MIN_POWER_KW = BigDecimal.ONE;

    private final String source;
    private final MobilithekSyncProperties.Feed feed;
    private final String registerSource;
    private final String countryCode;
    private final Set<String> emittedEvseIds;
    private final Instant fetchedAt;
    final Counters counters = new Counters();

    /**
     * @param emittedEvseIds normalized EVSE-IDs already emitted this run, shared across feeds and added to here
     * @param fetchedAt      when the package was fetched: the timestamp of a station neither it nor its site dates,
     *                       which most publishers leave out and the provenance record requires
     */
    AfirSiteMapper(String source, MobilithekSyncProperties.Feed feed, String registerSource, String countryCode,
                   Set<String> emittedEvseIds, Instant fetchedAt) {
        this.source = source;
        this.feed = feed;
        this.registerSource = registerSource;
        this.countryCode = countryCode;
        this.emittedEvseIds = emittedEvseIds;
        this.fetchedAt = fetchedAt;
    }

    List<SourceStation> map(JsonNode site) {
        List<SourceStation> stations = new ArrayList<>();
        for (JsonNode station : items(field(site, "energyInfrastructureStation"))) {
            SourceStation mapped = station(site, station);
            if (mapped != null) stations.add(mapped);
        }
        return stations;
    }

    private SourceStation station(JsonNode site, JsonNode station) {
        String id = idOf(station);
        if (id == null) return null;
        JsonNode location = field(station, LOCATION_REFERENCE);
        JsonNode siteLocation = field(site, LOCATION_REFERENCE);
        Position position = position(location);
        if (position == null) position = position(siteLocation);
        if (position == null) {
            counters.withoutPosition++;
            return null;
        }
        JsonNode address = find(location, "address");
        if (address == null) address = find(siteLocation, "address");
        String country = upper(text(field(address, "countryCode")));
        if (country == null) country = countryCode;
        if (!countryCode.equals(country)) {
            counters.foreign++;
            return null;
        }

        String key = feed.key() + "/" + id;
        List<SourceStation.SourceChargePoint> chargePoints = chargePoints(key, station);
        if (chargePoints.isEmpty()) return null;

        String stationName = text(field(station, "name"));
        String operator = operatorName(stationName, field(station, "operator"), field(site, "operator"));
        String name = displayName(stationName, text(field(site, "name")), operator);
        List<SourceStation.SourceLink> links = links(station);
        if (!links.isEmpty()) counters.linked++;
        counters.stations++;
        counters.chargePoints += chargePoints.size();
        return new SourceStation(source, key, name, street(address), text(field(address, "city")),
                text(field(address, "postcode")), country, operator, position.latitude(), position.longitude(), null,
                lastUpdated(site, station), List.of(), chargePoints, links, false);
    }

    private List<SourceStation.SourceChargePoint> chargePoints(String stationKey, JsonNode station) {
        List<SourceStation.SourceChargePoint> chargePoints = new ArrayList<>();
        for (JsonNode refillPoint : items(field(station, "refillPoint"))) {
            JsonNode point = field(refillPoint, "aegiElectricChargingPoint");
            if (point == null) point = refillPoint;
            String evseId = evseId(point);
            String normalized = EvseIds.normalize(evseId);
            if (normalized != null && !emittedEvseIds.add(normalized)) {
                counters.relayed++;
                continue;
            }
            if (normalized == null) counters.withoutEvseId++;
            // Keyed by EVSE-ID, which is unique per source; a refill point without one by its own id in its station.
            String chargePointId = normalized != null ? normalized : stationKey + "*" + idOf(point);
            chargePoints.add(new SourceStation.SourceChargePoint(chargePointId, evseId, connectors(point)));
        }
        return chargePoints;
    }

    /**
     * The identifier typed {@code evseId}, else the first that has the shape of one, else the refill point's own id
     * when that is one (EnBW, Tesla, chargecloud). As published, starred or not; the ingestion normalizes it.
     */
    private static String evseId(JsonNode point) {
        String shaped = null;
        for (JsonNode identifier : items(field(point, "externalIdentifier"))) {
            String value = identifier.isValueNode() ? identifier.asText() : text(field(identifier, "identifier"));
            String evse = evseShaped(value);
            if (evse == null) continue;
            if (EVSE_ID_TYPE.equals(identifierType(identifier))) return evse;
            if (shaped == null) shaped = evse;
        }
        return shaped != null ? shaped : evseShaped(idOf(point));
    }

    /** The published id if it is an EVSE-ID, or the starred EVSE-ID inside it (GP JOULE's {@code cp-DE*CNT*…-1}). */
    private static String evseShaped(String value) {
        String normalized = Datex.evseIdOf(value);
        if (normalized == null || !Datex.isEvseShaped(normalized)) return null;
        String published = Datex.blankToNull(value);
        return normalized.equals(EvseIds.normalize(published)) ? published : normalized;
    }

    private List<SourceStation.SourceLink> links(JsonNode station) {
        List<SourceStation.SourceLink> links = new ArrayList<>();
        for (JsonNode identifier : items(field(station, "externalIdentifier"))) {
            if (identifier.isValueNode()) continue;
            String value = text(field(identifier, "identifier"));
            if (value != null && DIGITS.matcher(value).matches()
                    && REGISTER_ID_TYPES.contains(identifierType(identifier)))
                links.add(new SourceStation.SourceLink(registerSource, value));
        }
        return links;
    }

    private record Plug(String type, BigDecimal powerKw) {
    }

    /** One connector per distinct type and rating, with the number of plugs as quantity — as for Spain. */
    private static List<SourceStation.SourceConnector> connectors(JsonNode point) {
        Map<Plug, Integer> merged = new LinkedHashMap<>();
        BigDecimal pointPower = kilowatts(text(first(field(point, "availableChargingPower"))));
        for (JsonNode connector : items(field(point, "connector"))) {
            String type = ConnectorTypes.fromDatex(enumValue(field(connector, "connectorType")));
            if (type == null) continue;
            BigDecimal power = kilowatts(text(field(connector, "maxPowerAtSocket")));
            merged.merge(new Plug(type, power != null ? power : pointPower), 1, Integer::sum);
        }
        List<SourceStation.SourceConnector> connectors = new ArrayList<>(merged.size());
        merged.forEach((plug, quantity) ->
                connectors.add(new SourceStation.SourceConnector(plug.type(), plug.powerKw(), quantity)));
        return connectors;
    }

    /** Watts to kilowatts; {@code null} below 1 kW, which VW Group Charging writes as 0 for every charge point. */
    private static BigDecimal kilowatts(String watts) {
        if (watts == null) return null;
        BigDecimal kilowatts;
        try {
            kilowatts = new BigDecimal(watts.trim()).movePointLeft(3);
        } catch (NumberFormatException _) {
            return null;
        }
        if (kilowatts.compareTo(MIN_POWER_KW) < 0) return null;
        BigDecimal stripped = kilowatts.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    /**
     * {@code legalName}, then {@code name}, then the feed's publisher. An operator id ({@code DE*EWE}) is no name, and
     * neither is the station's own name, which VW Group Charging writes into {@code legalName} for all 4.651 stations.
     */
    private String operatorName(String stationName, JsonNode... operators) {
        for (JsonNode operator : operators) {
            JsonNode organisation = field(operator, "afacAnOrganisation");
            if (organisation == null) organisation = operator;
            for (String candidate : new String[]{text(field(organisation, "legalName")), text(field(organisation, "name"))})
                if (candidate != null && !OPERATOR_ID.matcher(candidate).matches() && !candidate.equals(stationName))
                    return candidate;
        }
        return feed.publisher();
    }

    /** The station's or the site's name where it reads as one, otherwise the operator — as the register falls back. */
    private static String displayName(String stationName, String siteName, String operator) {
        if (stationName != null && WORD.matcher(stationName).find()) return stationName;
        if (siteName != null && WORD.matcher(siteName).find()) return siteName;
        return operator;
    }

    private static String street(JsonNode address) {
        String street = null;
        String number = null;
        for (JsonNode line : items(field(address, "addressLine"))) {
            String type = enumValue(field(line, "type"));
            if ("street".equals(type)) street = text(field(line, "text"));
            else if ("houseNumber".equals(type)) number = text(field(line, "text"));
        }
        if (street == null) return null;
        return number == null ? street : street + " " + number;
    }

    private record Position(double latitude, double longitude) {
    }

    private static Position position(JsonNode location) {
        for (String name : new String[]{"coordinatesForDisplay", "pointCoordinates"}) {
            JsonNode coordinates = find(location, name);
            if (coordinates == null) continue;
            JsonNode point = first(coordinates);
            try {
                String latitude = text(field(point, "latitude"));
                String longitude = text(field(point, "longitude"));
                if (latitude != null && longitude != null)
                    return new Position(Double.parseDouble(latitude), Double.parseDouble(longitude));
            } catch (NumberFormatException _) {
                // try the other spelling
            }
        }
        return null;
    }

    private Instant lastUpdated(JsonNode site, JsonNode station) {
        Instant stated = instant(text(field(station, "lastUpdated")));
        if (stated == null) stated = instant(text(field(site, "lastUpdated")));
        return stated != null ? stated : fetchedAt;
    }

    private static Instant instant(String text) {
        if (text == null) return null;
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    private static String upper(String text) {
        return text == null ? null : text.toUpperCase(Locale.ROOT);
    }

    // --- tolerant accessors over both syntaxes' trees ---

    /** JSON writes {@code idG}, XML the {@code id} attribute. */
    private static String idOf(JsonNode node) {
        String id = text(field(node, "idG"));
        return id != null ? id : text(field(node, "id"));
    }

    /** A field by name, also with its first letter's case flipped ({@code FacilityLocation}). */
    static JsonNode field(JsonNode node, String name) {
        if (node == null || !node.isObject()) return null;
        JsonNode value = node.get(name);
        if (value != null) return value;
        char first = name.charAt(0);
        String flipped = (Character.isUpperCase(first) ? Character.toLowerCase(first) : Character.toUpperCase(first))
                + name.substring(1);
        return node.get(flipped);
    }

    /** The node's items: an array's elements, a single object as itself, nothing for a missing node. */
    static Iterable<JsonNode> items(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return List.of();
        return node.isArray() ? node : List.of(node);
    }

    private static JsonNode first(JsonNode node) {
        Iterator<JsonNode> items = items(node).iterator();
        return items.hasNext() ? items.next() : null;
    }

    /** The first descendant (or the node itself) carrying {@code name}, depth first. */
    static JsonNode find(JsonNode node, String name) {
        if (node == null) return null;
        JsonNode direct = field(node, name);
        if (direct != null) return direct;
        for (JsonNode child : node) {
            JsonNode found = find(child, name);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Text of a plain value, of a multilingual {@code values} list (the first non-blank), or of a {@code value} field.
     */
    static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isValueNode()) return Datex.blankToNull(node.asText());
        if (node.isArray()) {
            for (JsonNode item : node) {
                String text = text(item);
                if (text != null) return text;
            }
            return null;
        }
        JsonNode values = field(node, "values");
        if (values != null) return text(values);
        return text(field(node, "value"));
    }

    /** What an {@code externalIdentifier} identifies: its extended type wherever it sits, else its plain type. */
    private static String identifierType(JsonNode identifier) {
        String extended = text(find(identifier, "extendedValueG"));
        return extended != null ? extended : enumValue(field(identifier, "typeOfIdentifier"));
    }

    /** An enumeration: bare text in XML, {@code {"value": …}} in JSON, {@code extendedValueG} for extensions. */
    static String enumValue(JsonNode node) {
        if (node == null) return null;
        if (node.isValueNode()) return Datex.blankToNull(node.asText());
        String extended = text(field(node, "extendedValueG"));
        return extended != null ? extended : text(field(node, "value"));
    }
}
