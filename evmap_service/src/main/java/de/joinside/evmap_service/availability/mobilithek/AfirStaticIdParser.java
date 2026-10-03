package de.joinside.evmap_service.availability.mobilithek;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import de.joinside.evmap_service.sync.EvseIds;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the static AFIR description of an operator ({@code AFIR-Recharging-Static}) for one thing only: which EVSE-ID
 * belongs to each of its internal refill point ids.
 * <p>
 * Some operators publish internal ids in their live feed — Wirelane UUIDs, eRound hashes — and their EVSE-IDs only in
 * the static one, as the refill point's {@code externalIdentifier} of type {@code evseId}. With this translation their
 * live status joins on the EVSE-ID like every other feed's, still exactly (ADR 0015, L5 open point b). Nothing else of
 * the static description is read; stations and connectors come from the national register through {@code sync}.
 * <p>
 * A static package is large — eRound's is 121 MB unpacked — so both readers stream, holding one refill point at a
 * time.
 */
final class AfirStaticIdParser {

    /** JSON: the keys a refill point appears under. */
    private static final String ELECTRIC_CHARGING_POINT = "aegiElectricChargingPoint";
    private static final String REFILL_POINT_KEY = "aegiRefillPoint";
    /** XML: the element, whose {@code xsi:type} says electric or not. */
    private static final String REFILL_POINT_ELEMENT = "refillPoint";
    private static final String EXTERNAL_IDENTIFIER = "externalIdentifier";
    private static final String IDENTIFIER = "identifier";
    private static final String TYPE_OF_IDENTIFIER = "typeOfIdentifier";
    private static final String EVSE_ID_TYPE = "evseId";

    private AfirStaticIdParser() {
    }

    /**
     * @return normalized internal id → normalized EVSE-ID, for every refill point that names one. A refill point whose
     * id already is its EVSE-ID maps onto itself, which is harmless.
     */
    static Map<String, String> parse(InputStream body) throws IOException {
        BufferedInputStream in = new BufferedInputStream(body);
        Map<String, String> evseIds = new HashMap<>();
        if (Datex.startsWithMarkup(in)) readXml(in, evseIds);
        else readJson(in, evseIds);
        return evseIds;
    }

    /**
     * Picks the EVSE-ID among a refill point's external identifiers: the one typed {@code evseId} if there is one,
     * otherwise the first that has the shape of one. A type is a claim by the publisher; the shape check keeps an
     * untyped hash from being taken for an EVSE-ID.
     */
    private static void add(Map<String, String> into, String internalId, List<Identifier> identifiers) {
        String key = EvseIds.normalize(internalId);
        if (key == null) return;
        String typed = null;
        String shaped = null;
        for (Identifier identifier : identifiers) {
            String evseId = Datex.evseIdOf(identifier.value());
            if (evseId == null) continue;
            if (typed == null && identifier.typedAsEvseId()) typed = evseId;
            if (shaped == null && Datex.isEvseShaped(evseId)) shaped = evseId;
        }
        String chosen = typed != null ? typed : shaped;
        if (chosen != null) into.put(key, chosen);
    }

    private record Identifier(String value, boolean typedAsEvseId) {
    }

    /**
     * The JSON syntax: each refill point object carries its internal id as {@code idG} and a list of
     * {@code externalIdentifier} objects, each with an {@code identifier} and a {@code typeOfIdentifier} whose
     * {@code extendedValueG} is {@code evseId} for the one wanted.
     */
    private static void readJson(InputStream in, Map<String, String> into) throws IOException {
        try (JsonParser parser = Datex.JSON.createParser(in)) {
            for (JsonToken token = parser.nextToken(); token != null; token = parser.nextToken())
                if (token == JsonToken.FIELD_NAME && isRefillPoint(parser.currentName())) readRefillPoints(parser, into);
        }
    }

    private static boolean isRefillPoint(String field) {
        return ELECTRIC_CHARGING_POINT.equals(field) || REFILL_POINT_KEY.equals(field);
    }

    private static void readRefillPoints(JsonParser parser, Map<String, String> into) throws IOException {
        parser.nextToken();
        JsonNode node = Datex.JSON.readTree(parser);
        for (JsonNode refillPoint : node.isArray() ? node : List.of(node))
            add(into, refillPoint.path("idG").asText(null), jsonIdentifiers(refillPoint.path(EXTERNAL_IDENTIFIER)));
    }

    private static List<Identifier> jsonIdentifiers(JsonNode node) {
        List<Identifier> identifiers = new ArrayList<>();
        for (JsonNode identifier : node.isArray() ? node : List.of(node)) {
            JsonNode type = identifier.path(TYPE_OF_IDENTIFIER);
            boolean typed = EVSE_ID_TYPE.equalsIgnoreCase(type.path("extendedValueG").asText(""))
                    || EVSE_ID_TYPE.equalsIgnoreCase(type.path("value").asText(""))
                    || EVSE_ID_TYPE.equalsIgnoreCase(type.asText(""));
            identifiers.add(new Identifier(identifier.path(IDENTIFIER).asText(null), typed));
        }
        return identifiers;
    }

    /**
     * The XML syntax: each {@code refillPoint} element carries its internal id as the {@code id} attribute and
     * {@code externalIdentifier} children with an {@code identifier} and a {@code typeOfIdentifier}.
     */
    private static void readXml(InputStream in, Map<String, String> into) throws IOException {
        try {
            XMLStreamReader xml = Datex.XML.createXMLStreamReader(in);
            try {
                while (xml.hasNext())
                    if (xml.next() == XMLStreamConstants.START_ELEMENT && REFILL_POINT_ELEMENT.equals(xml.getLocalName()))
                        add(into, xml.getAttributeValue(null, "id"), xmlIdentifiers(xml));
            } finally {
                xml.close();
            }
        } catch (XMLStreamException e) {
            throw new IOException("Unreadable DATEX II XML package", e);
        }
    }

    /**
     * Reads one {@code refillPoint} up to its end tag and collects its external identifiers. An extended type is
     * written as text somewhere inside {@code typeOfIdentifier} or its extension, so any {@code evseId} text inside an
     * {@code externalIdentifier} marks it.
     */
    private static List<Identifier> xmlIdentifiers(XMLStreamReader xml) throws XMLStreamException {
        XmlRefillPoint refillPoint = new XmlRefillPoint();
        while (refillPoint.open() && xml.hasNext()) {
            switch (xml.next()) {
                case XMLStreamConstants.END_ELEMENT -> refillPoint.end();
                case XMLStreamConstants.START_ELEMENT -> refillPoint.start(xml);
                case XMLStreamConstants.CHARACTERS -> refillPoint.text(xml.getText());
                default -> {
                    // Comments, whitespace events and the like carry nothing.
                }
            }
        }
        return refillPoint.identifiers;
    }

    /** Where the XML reader is inside one {@code refillPoint}, and what it has collected so far. */
    private static final class XmlRefillPoint {
        final List<Identifier> identifiers = new ArrayList<>();
        private int depth = 1;
        /** The depth of the {@code externalIdentifier} being read, or -1 outside one. */
        private int externalDepth = -1;
        private String value;
        private boolean typed;

        boolean open() {
            return depth > 0;
        }

        private boolean insideExternalIdentifier() {
            return externalDepth > 0;
        }

        void start(XMLStreamReader xml) throws XMLStreamException {
            String name = xml.getLocalName();
            if (!insideExternalIdentifier() && EXTERNAL_IDENTIFIER.equals(name)) {
                depth++;
                externalDepth = depth;
                value = null;
                typed = false;
            } else if (insideExternalIdentifier() && IDENTIFIER.equals(name)) {
                // Consumes the element up to its end tag, so the depth does not change.
                value = Datex.blankToNull(xml.getElementText());
            } else {
                depth++;
            }
        }

        void end() {
            if (depth == externalDepth) {
                identifiers.add(new Identifier(value, typed));
                externalDepth = -1;
            }
            depth--;
        }

        void text(String text) {
            if (insideExternalIdentifier() && EVSE_ID_TYPE.equalsIgnoreCase(text.trim())) typed = true;
        }
    }
}
