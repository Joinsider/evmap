package de.joinside.evmap_service.mobilithek;

import com.fasterxml.jackson.databind.json.JsonMapper;
import de.joinside.evmap_service.sync.EvseIds;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the readers of Mobilithek packages share — the live statuses and id translations in
 * {@code availability.mobilithek}, the static descriptions in {@code sync.mobilithek}: telling the two syntaxes
 * apart, reading XML safely, and recognising an EVSE-ID in whatever a publisher puts in its id fields.
 */
public final class Datex {
    public static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * XML from a third party: DTDs and external entities refused, namespaces resolved so that elements can be matched
     * by local name whatever prefixes a publisher chooses.
     */
    public static final XMLInputFactory XML = secureXmlFactory();

    /**
     * The shape of an eMI3 EVSE-ID once {@link EvseIds} has removed the separators: country, operator, then the
     * {@code E} that marks an EVSE. Used to count and to choose among identifiers, never to guess: matching stays by
     * exact identifier.
     */
    private static final Pattern EVSE_ID_SHAPE = Pattern.compile("^[A-Z]{2}[A-Z0-9]{3}E[A-Z0-9]+$");

    /**
     * A UUID or hash with its dashes removed. Wirelane and eRound publish such internal ids, and one in a few dozen
     * happens to fit {@link #EVSE_ID_SHAPE} — {@code ae0b0ee4…} reads as {@code AE}, {@code 0B0}, {@code E…}. A real
     * EVSE-ID is far shorter than 24 characters.
     */
    private static final Pattern HEX_HASH = Pattern.compile("^[0-9A-F]{24,}$");

    /**
     * An EVSE-ID in its starred spelling inside a longer id. GP JOULE wraps theirs as
     * {@code cp-DE*CNT*EP90046*002*1-1}; the stars make the embedded id unambiguous, so it is taken literally and
     * the join stays exact. Without stars nothing is extracted — a hash can contain any letters.
     */
    private static final Pattern EMBEDDED_EVSE_ID =
            Pattern.compile("(?<![A-Za-z0-9])([A-Za-z]{2}\\*[A-Za-z0-9]{3}\\*[Ee][A-Za-z0-9*]*[A-Za-z0-9])");

    private Datex() {
    }

    private static XMLInputFactory secureXmlFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        return factory;
    }

    /**
     * Whether the first character that is not whitespace or a byte order mark is {@code <}. The broker hands a
     * package out as it was delivered, and operators deliver either syntax whatever their offering says.
     */
    public static boolean startsWithMarkup(BufferedInputStream in) throws IOException {
        in.mark(256);
        try {
            for (int i = 0; i < 256; i++) {
                int next = in.read();
                if (next == '<') return true;
                if (next == -1 || !(Character.isWhitespace(next) || next == 0xEF || next == 0xBB || next == 0xBF))
                    return false;
            }
            return false;
        } finally {
            in.reset();
        }
    }

    /** The normalized EVSE-ID of a published id: the id itself, or the starred EVSE-ID embedded in it. */
    public static String evseIdOf(String id) {
        String normalized = EvseIds.normalize(id);
        if (normalized == null || isEvseShaped(normalized)) return normalized;
        Matcher embedded = EMBEDDED_EVSE_ID.matcher(id);
        return embedded.find() ? EvseIds.normalize(embedded.group(1)) : normalized;
    }

    public static boolean isEvseShaped(String normalizedId) {
        return EVSE_ID_SHAPE.matcher(normalizedId).matches() && !HEX_HASH.matcher(normalizedId).matches();
    }

    public static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
