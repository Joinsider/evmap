package de.joinside.evmap_service.availability.mobilithek;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.joinside.evmap_service.availability.LiveAvailability;
import de.joinside.evmap_service.sync.EvseIds;

import java.io.IOException;
import java.io.InputStream;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads one DATEX II v3 package of the AFIR dynamic profile ({@code AFIR-Recharging-Dynamic-01-00-00_Delta}, JSON)
 * into the charge points it reports and whether it is a full snapshot or a delta.
 * <p>
 * Split out of the provider so the mapping is testable against a fixture rather than the broker. The package nests
 * {@code messageContainer → payload[] → aegiEnergyInfrastructureStatusPublication → energyInfrastructureSiteStatus[]
 * → energyInfrastructureStationStatus[] → refillPointStatus[] → aegiElectricChargingPointStatus}, but only the
 * innermost object carries anything this app shows, so the parser streams through the document and reads just
 * those objects as trees. A full snapshot of a large operator is tens of megabytes; holding all of it as a tree
 * would be the API container's largest allocation for no benefit.
 * <p>
 * Nothing here trusts the publishers to follow the schema to the letter: a charge point status is recognised by
 * its key wherever it appears, and an array of one is read the same as a bare object.
 */
final class AfirStatusJson {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Both keys DATEX uses for a charge point's status; the electric one is what every AFIR feed sends. */
    private static final String ELECTRIC_CHARGING_POINT_STATUS = "aegiElectricChargingPointStatus";
    private static final String REFILL_POINT_STATUS = "aegiRefillPointStatus";
    private static final String CODED_EXCHANGE_PROTOCOL = "codedExchangeProtocol";
    private static final String PUBLICATION_TIME = "publicationTime";
    private static final String DELTA_PULL = "deltaPull";
    private static final String DELTA_PUSH = "deltaPush";

    /**
     * The shape of an eMI3 EVSE-ID once {@link EvseIds} has removed the separators: country, operator, then the
     * {@code E} that marks an EVSE. Used only to count, never to filter — matching is by exact identifier, so an
     * internal id that is not an EVSE-ID cannot produce a wrong answer, only none.
     */
    private static final Pattern EVSE_ID_SHAPE = Pattern.compile("^[A-Z]{2}[A-Z0-9]{3}E[A-Z0-9]+$");

    /** Operators publish local times without an offset now and then; the feed is German. */
    private static final ZoneId PUBLISHER_ZONE = ZoneId.of("Europe/Berlin");

    private AfirStatusJson() {
    }

    /**
     * One package, as far as this app is concerned.
     *
     * @param delta       {@code true} when the package only updates the charge points it names; {@code false}
     *                    for a full snapshot, which replaces everything known about the feed. A package without
     *                    an exchange protocol is read as a delta: applying a snapshot as a delta keeps a few
     *                    removed charge points for one more round, applying a delta as a snapshot would wipe
     *                    the operator's whole map.
     * @param chargePoints the reported statuses by normalized EVSE-ID; the newest wins where a package names one
     *                    twice
     * @param ignored     charge point statuses read but not kept: no id, or a status that describes nothing a
     *                    driver could arrive at ({@code planned}, {@code removed})
     * @param evseShaped  how many of {@code chargePoints} have the shape of an EVSE-ID — the number that decides
     *                    whether the static feeds are needed to translate internal ids (ADR 0015, L5 open point b)
     */
    record Package(boolean delta, Map<String, Reported> chargePoints, int ignored, int evseShaped) {
    }

    /** One charge point's status as the package reports it, before attribution is attached. */
    record Reported(String status, Instant observedAt) {
    }

    static Package parse(InputStream json) throws IOException {
        Map<String, Reported> chargePoints = new LinkedHashMap<>();
        boolean delta = true;
        Instant publicationTime = null;
        int ignored = 0;

        try (JsonParser parser = MAPPER.createParser(json)) {
            for (JsonToken token = parser.nextToken(); token != null; token = parser.nextToken()) {
                if (token != JsonToken.FIELD_NAME) continue;
                String field = parser.currentName();
                switch (field) {
                    case ELECTRIC_CHARGING_POINT_STATUS, REFILL_POINT_STATUS -> {
                        parser.nextToken();
                        JsonNode node = MAPPER.readTree(parser);
                        for (JsonNode status : node.isArray() ? node : List.of(node))
                            if (!add(chargePoints, status, publicationTime)) ignored++;
                    }
                    case CODED_EXCHANGE_PROTOCOL -> {
                        parser.nextToken();
                        String protocol = textOf(MAPPER.readTree(parser));
                        if (protocol != null) delta = DELTA_PULL.equals(protocol) || DELTA_PUSH.equals(protocol);
                    }
                    case PUBLICATION_TIME -> {
                        if (parser.nextToken() == JsonToken.VALUE_STRING && publicationTime == null)
                            publicationTime = instant(parser.getText());
                    }
                    default -> {
                        // Descend: everything else is either a container on the way down or a value nobody reads.
                    }
                }
            }
        }

        int evseShaped = 0;
        for (String id : chargePoints.keySet())
            if (EVSE_ID_SHAPE.matcher(id).matches()) evseShaped++;
        return new Package(delta, chargePoints, ignored, evseShaped);
    }

    /** @return whether the status was kept */
    private static boolean add(Map<String, Reported> into, JsonNode status, Instant publicationTime) {
        String evseId = EvseIds.normalize(textOf(status.path("reference").path("idG")));
        if (evseId == null) return false;
        String live = toLiveAvailability(textOf(status.path("status")), textOf(status.path("operationStatus")));
        if (live == null) return false;

        Instant observedAt = instant(textOf(status.path("lastUpdated")));
        if (observedAt == null) observedAt = publicationTime;
        Reported reported = new Reported(live, observedAt);
        into.merge(evseId, reported, AfirStatusJson::newer);
        return true;
    }

    private static Reported newer(Reported held, Reported candidate) {
        if (candidate.observedAt() == null) return held;
        if (held.observedAt() == null) return candidate;
        return candidate.observedAt().isAfter(held.observedAt()) ? candidate : held;
    }

    /**
     * Collapses DATEX's thirteen refill point states onto the four the client knows — the same collapse
     * {@code OcpiStatus} makes for OCPI, for the same reasons.
     *
     * @return the {@link LiveAvailability} token, or {@code null} when the status describes nothing a driver could
     * arrive at and the charge point should count as unreported
     */
    static String toLiveAvailability(String status, String operationStatus) {
        // An operator saying "not in operation" or "technical defect" is the stronger statement: a post can be
        // free in the sense of unoccupied and still be switched off.
        if (operationStatus != null) {
            switch (operationStatus) {
                case "notInOperation", "notInOperationAbnormal", "technicalDefect" -> {
                    return LiveAvailability.OUT_OF_ORDER;
                }
                default -> {
                    // inOperation, limitedOperation, unknown: the refill point status decides.
                }
            }
        }
        if (status == null) return null;
        return switch (status) {
            case "available" -> LiveAvailability.AVAILABLE;
            // Someone else's session, booking, or car in the bay — the driver cannot pull in either way.
            case "charging", "occupied", "reserved", "blocked" -> LiveAvailability.OCCUPIED;
            // Broken, switched off, or otherwise not usable: "do not drive here", whatever the reason.
            case "faulted", "inoperative", "outOfOrder", "unavailable" -> LiveAvailability.OUT_OF_ORDER;
            case "unknown" -> LiveAvailability.UNKNOWN;
            // planned is not built, removed is gone, outOfStock is a fuel concept; extendedG is a publisher's
            // private value. None of them describes a charge point a driver could use.
            default -> null;
        };
    }

    /** DATEX enums arrive as {@code {"value": "…"}}; a bare string is accepted too. */
    private static String textOf(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isArray()) return node.isEmpty() ? null : textOf(node.get(0));
        if (node.isObject()) return textOf(node.get("value"));
        String text = node.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static Instant instant(String text) {
        if (text == null) return null;
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeException offsetMissing) {
            try {
                return LocalDateTime.parse(text).atZone(PUBLISHER_ZONE).toInstant();
            } catch (DateTimeException unreadable) {
                return null;
            }
        }
    }
}
