package de.joinside.evmap_service.sync;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical connector vocabulary written into {@code master.charging_connector.connector_type}.
 * <p>
 * Every source names the same physical plug differently — BNetzA writes German equipment labels
 * ("AC Typ 2 Steckdose"), Open Charge Map writes community-maintained English titles
 * ("Type 2 (Socket Only)"). The API filters connectors by exact string match and the iOS client sends
 * a closed enum ({@code ConnectorType.swift}), so both adapters must collapse their label space onto
 * the values here or the filter silently matches nothing.
 * <p>
 * Adding a constant here is only half a change: the iOS enum has to learn it too, otherwise the type
 * is stored but unfilterable.
 */
public final class ConnectorTypes {
    public static final String TYPE_1 = "Type 1";
    public static final String TYPE_2 = "Type 2";
    public static final String CCS = "CCS";
    public static final String CHADEMO = "CHAdeMO";
    public static final String TESLA = "Tesla";
    public static final String SCHUKO = "Schuko";
    public static final String CEE = "CEE";
    /**
     * Megawatt Charging System — truck charging. Present in the BNetzA register (24 charge points in
     * the 2026-07-07 edition) but deliberately absent from the iOS enum: it is not a passenger-car
     * plug, so it is stored for completeness and simply never offered as a filter.
     */
    public static final String MCS = "MCS";

    /** DATEX II v3 {@code ConnectorTypeEnum} values, which name a plug rather than describe it. */
    private static final Map<String, String> DATEX_TYPES = Map.of(
            "iec62196T2", TYPE_2,
            "iec62196T2COMBO", CCS,
            "iec62196T1COMBO", CCS,
            "iec62196T1", TYPE_1,
            "chademo", CHADEMO,
            "domesticF", SCHUKO,
            "iec62196T3A", "Type 3A",
            "iec62196T3C", "Type 3C");
    private static final Pattern DATEX_DOMESTIC_SOCKET = Pattern.compile("domestic([A-Z])");

    /** Width of {@code master.charging_connector.connector_type}; unmapped labels are kept but clipped. */
    private static final int MAX_LENGTH = 64;

    /**
     * First match wins, so the order is the specificity ranking:
     * CCS and Tesla labels routinely also say "Type 2" and must be tried before it; "Schuko" before
     * CEE, because "CEE 7/4 - Schuko - Type F" and "Europlug 2-Pin (CEE 7/16)" carry a CEE
     * designation but are domestic 230 V sockets, not the industrial CEE connector.
     */
    private static final List<Rule> RULES = List.of(
            new Rule(CHADEMO, "chademo"),
            new Rule(MCS, "megawatt", "mcs"),
            new Rule(TESLA, "tesla", "nacs"),
            new Rule(CCS, "ccs", "combo"),
            new Rule(SCHUKO, "schuko", "type f", "europlug"),
            new Rule(CEE, "cee", "commando", "60309"),
            new Rule(TYPE_2, "typ 2", "type 2"),
            new Rule(TYPE_1, "typ 1", "type 1", "j1772"));

    private record Rule(String type, List<String> needles) {
        Rule(String type, String... needles) {
            this(type, List.of(needles));
        }

        boolean matches(String haystack) {
            for (String needle : needles) {
                if (haystack.contains(needle)) return true;
            }
            return false;
        }
    }

    private ConnectorTypes() {
    }

    /**
     * Maps a DATEX II v3 connector type onto the canonical vocabulary — the Spanish register and the Mobilithek's
     * AFIR feeds both publish these enumerations rather than labels. The domestic sockets other than Schuko
     * ({@code domesticE}, {@code domesticA}, …) and the Scame types get a readable label and stay unfilterable, the
     * intended degradation; anything else goes through {@link #normalize(String)}.
     */
    public static String fromDatex(String datexType) {
        if (datexType == null) return null;
        String mapped = DATEX_TYPES.get(datexType);
        if (mapped != null) return mapped;
        if (datexType.startsWith("iec60309")) return CEE;
        Matcher domestic = DATEX_DOMESTIC_SOCKET.matcher(datexType);
        if (domestic.matches()) return "Type " + domestic.group(1);
        return normalize(datexType);
    }

    /**
     * Maps a free-text upstream connector label onto the canonical vocabulary.
     * <p>
     * Order is significant, because upstream labels routinely name two standards at once and only the
     * more specific one identifies the plug: "CCS (Type 2)" and "DC Fahrzeugkupplung Typ Combo 2 (CCS)"
     * are CCS, not Type 2, and "DC Tesla Fahrzeugkupplung (Typ 2)" is a Tesla connector.
     *
     * @return the canonical type; the trimmed raw label (clipped to the column width) when nothing
     * matches, so an unrecognised plug degrades to unfilterable rather than to lost data;
     * {@code null} for blank labels and for OCM's explicit "Unknown", which carry no information
     */
    public static String normalize(String rawLabel) {
        if (rawLabel == null) return null;
        String trimmed = rawLabel.trim();
        if (trimmed.isEmpty()) return null;

        String haystack = trimmed.toLowerCase(Locale.ROOT);
        if (haystack.equals("unknown")) return null;

        for (Rule rule : RULES) {
            if (rule.matches(haystack)) return rule.type();
        }

        return trimmed.length() <= MAX_LENGTH ? trimmed : trimmed.substring(0, MAX_LENGTH);
    }
}
