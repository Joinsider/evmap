package de.joinside.evmap_service.sync.mobilithek;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.joinside.evmap_service.mobilithek.Datex;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

/**
 * Splits a static AFIR package ({@code EnergyInfrastructureTablePublication}) into its sites, one tree at a time.
 * <p>
 * A package is large — eRound's is 121 MB unpacked, chargecloud's 112 MB — so neither syntax is read whole: the JSON
 * reader streams to each {@code energyInfrastructureSite} and reads only that subtree, the XML reader builds the same
 * shape of tree from one site element. Publishers deliver either syntax whatever their offering says (ladenetz.de and
 * ladebusiness send XML), so both end up as the same {@link JsonNode} for {@link AfirSiteMapper}:
 * <ul>
 *     <li>an element is an object of its children; a repeated child becomes an array;</li>
 *     <li>attributes are fields ({@code id}, {@code lang}, {@code order}); {@code xsi:type} and namespace
 *     declarations are dropped;</li>
 *     <li>a leaf is its text, or an object with a {@code value} field when it also has attributes — which is what the
 *     JSON syntax writes for a multilingual {@code <value lang="de">…</value>}.</li>
 * </ul>
 * The two trees are not identical — JSON writes {@code idG} where XML has the {@code id} attribute, and an enumeration
 * as {@code {"value": …}} where XML has bare text — and the mapper's accessors read either.
 */
final class AfirSiteReader {
    static final String SITE = "energyInfrastructureSite";
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private AfirSiteReader() {
    }

    static void read(InputStream body, Consumer<JsonNode> sites) throws IOException {
        BufferedInputStream in = new BufferedInputStream(body);
        if (Datex.startsWithMarkup(in)) readXml(in, sites);
        else readJson(in, sites);
    }

    private static void readJson(InputStream in, Consumer<JsonNode> sites) throws IOException {
        try (JsonParser parser = Datex.JSON.createParser(in)) {
            for (JsonToken token = parser.nextToken(); token != null; token = parser.nextToken()) {
                if (token != JsonToken.FIELD_NAME || !SITE.equals(parser.currentName())) continue;
                JsonToken value = parser.nextToken();
                if (value == JsonToken.START_ARRAY) {
                    while (parser.nextToken() == JsonToken.START_OBJECT) sites.accept(Datex.JSON.readTree(parser));
                } else if (value == JsonToken.START_OBJECT) {
                    sites.accept(Datex.JSON.readTree(parser));
                }
            }
        }
    }

    private static void readXml(InputStream in, Consumer<JsonNode> sites) throws IOException {
        try {
            XMLStreamReader xml = Datex.XML.createXMLStreamReader(in);
            try {
                while (xml.hasNext())
                    if (xml.next() == XMLStreamConstants.START_ELEMENT && SITE.equals(xml.getLocalName()))
                        sites.accept(element(xml));
            } finally {
                xml.close();
            }
        } catch (XMLStreamException e) {
            throw new IOException("Unreadable DATEX II XML package", e);
        }
    }

    /** Reads the element the reader stands on, up to and including its end tag. */
    private static JsonNode element(XMLStreamReader xml) throws XMLStreamException {
        ObjectNode node = NODES.objectNode();
        for (int i = 0; i < xml.getAttributeCount(); i++) {
            if (XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI.equals(xml.getAttributeNamespace(i))) continue;
            node.put(xml.getAttributeLocalName(i), xml.getAttributeValue(i));
        }
        StringBuilder text = new StringBuilder();
        boolean children = false;
        while (xml.hasNext()) {
            int event = xml.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                children = true;
                add(node, xml.getLocalName(), element(xml));
            } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                text.append(xml.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                break;
            }
        }
        if (children) return node;
        String value = text.toString().trim();
        if (node.isEmpty()) return NODES.textNode(value);
        node.put("value", value);
        return node;
    }

    private static void add(ObjectNode parent, String name, JsonNode child) {
        JsonNode existing = parent.get(name);
        if (existing == null) {
            parent.set(name, child);
        } else if (existing instanceof ArrayNode array) {
            array.add(child);
        } else {
            parent.set(name, NODES.arrayNode().add(existing).add(child));
        }
    }
}
