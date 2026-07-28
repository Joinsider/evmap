package de.joinside.evmap_service.sync;

import java.util.Locale;

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

    /** Width of {@code master.charging_connector.connector_type}; unmapped labels are kept but clipped. */
    private static final int MAX_LENGTH = 64;

    private ConnectorTypes() {
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

        if (haystack.contains("chademo")) return CHADEMO;
        if (haystack.contains("megawatt") || haystack.contains("mcs")) return MCS;
        // Before CCS and Type 2: Tesla connectors are described in terms of both.
        if (haystack.contains("tesla") || haystack.contains("nacs")) return TESLA;
        if (haystack.contains("ccs") || haystack.contains("combo")) return CCS;
        // Before CEE: "CEE 7/4 - Schuko - Type F" and "Europlug 2-Pin (CEE 7/16)" both carry a CEE
        // designation but are domestic 230 V sockets, not the industrial CEE connector a driver
        // filtering for CEE is looking for.
        if (haystack.contains("schuko") || haystack.contains("type f") || haystack.contains("europlug"))
            return SCHUKO;
        if (haystack.contains("cee") || haystack.contains("commando") || haystack.contains("60309")) return CEE;
        if (haystack.contains("typ 2") || haystack.contains("type 2")) return TYPE_2;
        if (haystack.contains("typ 1") || haystack.contains("type 1") || haystack.contains("j1772")) return TYPE_1;

        return trimmed.length() <= MAX_LENGTH ? trimmed : trimmed.substring(0, MAX_LENGTH);
    }
}
