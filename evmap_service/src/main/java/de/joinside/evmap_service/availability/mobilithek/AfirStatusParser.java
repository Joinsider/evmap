package de.joinside.evmap_service.availability.mobilithek;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import de.joinside.evmap_service.availability.LiveAvailability;
import de.joinside.evmap_service.availability.Observations;
import de.joinside.evmap_service.mobilithek.Datex;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads one DATEX II v3 package of the AFIR dynamic profile ({@code AFIR-Recharging-Dynamic-01-00-00_Delta}) into
 * the charge points it reports and whether it is a full snapshot or a delta.
 * <p>
 * Split out of the provider so the mapping is testable against a fixture rather than the broker. The broker hands a
 * package out as it was delivered, and operators deliver either syntax: most send JSON, ladenetz.de and
 * ladebusiness send XML although their offerings say JSON. The first character decides, and both readers fill the
 * same {@link PackageReader}.
 * <p>
 * Either way the package nests {@code messageContainer → payload → energyInfrastructureSiteStatus →
 * energyInfrastructureStationStatus → refillPointStatus}, and only the innermost element carries anything this app
 * shows, so both readers stream through the document and look at just those. A full snapshot of a large operator
 * is tens of megabytes; holding all of it as a tree would be the API container's largest allocation for no benefit.
 * <p>
 * Nothing here trusts the publishers to follow the schema to the letter: a charge point status is recognised by
 * its name wherever it appears, and an array of one is read the same as a bare object.
 */
final class AfirStatusParser {
    /** JSON: both keys DATEX uses for a charge point's status; the electric one is what every AFIR feed sends. */
    private static final String ELECTRIC_CHARGING_POINT_STATUS = "aegiElectricChargingPointStatus";
    private static final String REFILL_POINT_STATUS_KEY = "aegiRefillPointStatus";
    /** XML: the element, whose {@code xsi:type} says electric or not — irrelevant here. */
    private static final String REFILL_POINT_STATUS_ELEMENT = "refillPointStatus";
    private static final String CODED_EXCHANGE_PROTOCOL = "codedExchangeProtocol";
    private static final String PUBLICATION_TIME = "publicationTime";
    private static final String REFERENCE = "reference";
    private static final String STATUS = "status";
    private static final String OPERATION_STATUS = "operationStatus";
    private static final String LAST_UPDATED = "lastUpdated";
    private static final String DELTA_PULL = "deltaPull";
    private static final String DELTA_PUSH = "deltaPush";

    /** How many ids of another shape a package reports for the log, so a feed's id scheme can be judged. */
    private static final int OTHER_ID_SAMPLES = 3;

    /** Operators publish local times without an offset now and then; the feed is German. */
    private static final ZoneId PUBLISHER_ZONE = ZoneId.of("Europe/Berlin");

    private AfirStatusParser() {
    }

    /**
     * One package, as far as this app is concerned.
     *
     * @param delta           {@code true} when the package only updates the charge points it names; {@code false}
     *                        for a full snapshot, which replaces everything known about the feed. A package without
     *                        an exchange protocol is read as a delta: applying a snapshot as a delta keeps a few
     *                        removed charge points for one more round, applying a delta as a snapshot would wipe
     *                        the operator's whole map.
     * @param chargePoints    the reported statuses by normalized EVSE-ID; the newest wins where a package names one
     *                        twice
     * @param ignored         charge point statuses read but not kept: no id, or a status that describes nothing a
     *                        driver could arrive at ({@code planned}, {@code removed})
     * @param evseShaped      how many of {@code chargePoints} have the shape of an EVSE-ID — the number that decides
     *                        whether the static feeds are needed to translate internal ids (ADR 0015, L5 open point b)
     * @param otherIdSamples  up to three ids that do not have that shape, as published, for the log
     * @param publishedAt     the package's own publication time, or {@code null} when it states none — what confirms
     *                        every status in it, however long ago that status last changed
     */
    record Package(boolean delta, Map<String, Reported> chargePoints, int ignored, int evseShaped,
                   List<String> otherIdSamples, Instant publishedAt) {
    }

    /** One charge point's status as the package reports it, before attribution is attached. */
    record Reported(String status, Instant observedAt) {
    }

    static Package parse(InputStream body) throws IOException {
        BufferedInputStream in = new BufferedInputStream(body);
        PackageReader reader = new PackageReader();
        if (Datex.startsWithMarkup(in)) XmlReader.read(in, reader);
        else JsonReader.read(in, reader);
        return reader.toPackage();
    }

    /** What has been read of one package so far, whatever its syntax. */
    private static final class PackageReader {
        private final Map<String, Reported> chargePoints = new LinkedHashMap<>();
        private final List<String> otherIdSamples = new ArrayList<>(OTHER_ID_SAMPLES);
        private boolean delta = true;
        private Instant publicationTime;
        private int ignored;

        void protocol(String protocol) {
            if (protocol != null) delta = DELTA_PULL.equals(protocol) || DELTA_PUSH.equals(protocol);
        }

        /** The first publication time is the one the statuses after it fall back on. */
        void publicationTime(String text) {
            if (publicationTime == null) publicationTime = instant(text);
        }

        void chargePoint(String id, String status, String operationStatus, String lastUpdated) {
            String evseId = Datex.evseIdOf(id);
            String live = toLiveAvailability(status, operationStatus);
            if (evseId == null || live == null) {
                ignored++;
                return;
            }
            Instant observedAt = instant(lastUpdated);
            Reported reported = new Reported(live, observedAt != null ? observedAt : publicationTime);
            Reported held = chargePoints.merge(evseId, reported,
                    (old, candidate) -> Observations.newer(old, candidate, Reported::observedAt));
            if (held == reported && otherIdSamples.size() < OTHER_ID_SAMPLES && !Datex.isEvseShaped(evseId)
                    && !otherIdSamples.contains(id.trim()))
                otherIdSamples.add(id.trim());
        }

        Package toPackage() {
            int evseShaped = (int) chargePoints.keySet().stream().filter(Datex::isEvseShaped).count();
            return new Package(delta, chargePoints, ignored, evseShaped, List.copyOf(otherIdSamples), publicationTime);
        }


        private static Instant instant(String text) {
            if (text == null) return null;
            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeException _) {
                try {
                    return LocalDateTime.parse(text).atZone(PUBLISHER_ZONE).toInstant();
                } catch (DateTimeException _) {
                    return null;
                }
            }
        }
    }

    /** The JSON syntax: DATEX enums as {@code {"value": "…"}}, references as {@code {"idG": "…"}}. */
    private static final class JsonReader {

        private JsonReader() {
        }

        static void read(InputStream in, PackageReader reader) throws IOException {
            try (JsonParser parser = Datex.JSON.createParser(in)) {
                for (JsonToken token = parser.nextToken(); token != null; token = parser.nextToken())
                    if (token == JsonToken.FIELD_NAME) read(parser.currentName(), parser, reader);
            }
        }

        /** Reads the value of {@code field} if it is one of the few this app needs; descends otherwise. */
        private static void read(String field, JsonParser parser, PackageReader reader) throws IOException {
            switch (field) {
                case ELECTRIC_CHARGING_POINT_STATUS, REFILL_POINT_STATUS_KEY -> statuses(nextTree(parser), reader);
                case CODED_EXCHANGE_PROTOCOL -> reader.protocol(textOf(nextTree(parser)));
                case PUBLICATION_TIME -> publicationTime(parser, reader);
                default -> {
                    // Everything else is either a container on the way down or a value nobody reads.
                }
            }
        }

        private static void statuses(JsonNode node, PackageReader reader) {
            for (JsonNode status : node.isArray() ? node : List.of(node))
                reader.chargePoint(textOf(status.path(REFERENCE).path("idG")), textOf(status.path(STATUS)),
                        textOf(status.path(OPERATION_STATUS)), textOf(status.path(LAST_UPDATED)));
        }

        private static void publicationTime(JsonParser parser, PackageReader reader) throws IOException {
            if (parser.nextToken() == JsonToken.VALUE_STRING) reader.publicationTime(parser.getText());
        }

        private static JsonNode nextTree(JsonParser parser) throws IOException {
            parser.nextToken();
            return Datex.JSON.readTree(parser);
        }

        /** DATEX enums arrive as {@code {"value": "…"}}; a bare string is accepted too. */
        private static String textOf(JsonNode node) {
            if (node == null || node.isMissingNode() || node.isNull()) return null;
            if (node.isArray()) return node.isEmpty() ? null : textOf(node.get(0));
            if (node.isObject()) return textOf(node.get("value"));
            return Datex.blankToNull(node.asText());
        }
    }

    /**
     * The XML syntax: enums as element text, references as {@code <fac:reference id="…" targetClass="…"/>}.
     * Elements are matched by local name, so the prefixes a publisher chooses do not matter. DTDs and external
     * entities are refused: the document comes from a third party.
     */
    private static final class XmlReader {
        private XmlReader() {
        }

        static void read(InputStream in, PackageReader reader) throws IOException {
            try {
                XMLStreamReader xml = Datex.XML.createXMLStreamReader(in);
                try {
                    while (xml.hasNext())
                        if (xml.next() == XMLStreamConstants.START_ELEMENT) read(xml, reader);
                } finally {
                    xml.close();
                }
            } catch (XMLStreamException e) {
                throw new IOException("Unreadable DATEX II XML package", e);
            }
        }

        private static void read(XMLStreamReader xml, PackageReader reader) throws XMLStreamException {
            switch (xml.getLocalName()) {
                case REFILL_POINT_STATUS_ELEMENT -> chargePoint(xml, reader);
                case CODED_EXCHANGE_PROTOCOL -> reader.protocol(Datex.blankToNull(xml.getElementText()));
                case PUBLICATION_TIME -> reader.publicationTime(Datex.blankToNull(xml.getElementText()));
                default -> {
                    // A container on the way down, or a value nobody reads.
                }
            }
        }

        /**
         * Reads one {@code refillPointStatus} up to its end tag. Only its direct children count: a nested
         * {@code plannedRefillPointStatus} carries a {@code status} of its own that must not overwrite the current one.
         */
        private static void chargePoint(XMLStreamReader xml, PackageReader reader) throws XMLStreamException {
            String id = null;
            String status = null;
            String operationStatus = null;
            String lastUpdated = null;
            int depth = 1;
            while (depth > 0 && xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.END_ELEMENT) {
                    depth--;
                } else if (event == XMLStreamConstants.START_ELEMENT && depth > 1) {
                    depth++;
                } else if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (xml.getLocalName()) {
                        case REFERENCE -> {
                            id = xml.getAttributeValue(null, "id");
                            depth++;
                        }
                        case STATUS -> status = Datex.blankToNull(xml.getElementText());
                        case OPERATION_STATUS -> operationStatus = Datex.blankToNull(xml.getElementText());
                        case LAST_UPDATED -> lastUpdated = Datex.blankToNull(xml.getElementText());
                        default -> depth++;
                    }
                }
            }
            reader.chargePoint(id, status, operationStatus, lastUpdated);
        }
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
}
