package de.joinside.evmap_service.sync;

import java.util.regex.Pattern;

/**
 * Comparison form of an EVSE-ID, the identifier live availability joins on.
 * <p>
 * The eMI3 / ISO 15118 identifier is written with optional separators, and publishers use all of
 * them: the 2026-07-28 BNetzA edition carries {@code DE*EBW*E912316*1}, {@code DEAEWE002501} and
 * {@code "DE CSA 24D 006"} in the same column, and MobiData BW's OCPI feed prefers the starred form
 * for 78 % of its live EVSEs and the compact one for the rest. Comparing the published strings would
 * therefore miss most genuine matches.
 * <p>
 * Normalizing means uppercasing and dropping everything outside {@code [A-Z0-9]}. This deliberately
 * lives in {@code sync} and is used by {@code availability} as well, because the two sides of the
 * join must normalize identically — two implementations that drift apart would not fail, they would
 * quietly stop matching, and the feature would look like a coverage problem rather than a bug.
 * <p>
 * The transformation is lossy in principle: {@code DE*ABC*E1} and {@code DEABC*E1} collapse onto the
 * same key. That is accepted — the separator positions carry no information the standard guarantees,
 * and an operator that distinguishes two charge points only by punctuation has bigger problems.
 * Collisions are counted where they matter (see {@code PostgresStationIngestionRepository}).
 */
public final class EvseIds {
    private static final Pattern NOT_ALPHANUMERIC = Pattern.compile("[^A-Z0-9]");
    /** Matches the column width of {@code master.charge_point.evse_id_normalized}. */
    private static final int MAX_LENGTH = 64;

    private EvseIds() {
    }

    /**
     * @return the comparison form, or {@code null} when {@code evseId} is blank or normalizes to
     * nothing. A null return means "this charge point cannot take part in the join", which is a
     * normal and common answer — only 30,3 % of declared Ladepunkte publish an EVSE-ID at all.
     */
    public static String normalize(String evseId) {
        if (evseId == null) return null;
        String normalized = NOT_ALPHANUMERIC.matcher(evseId.toUpperCase()).replaceAll("");
        if (normalized.isEmpty()) return null;
        return normalized.length() <= MAX_LENGTH ? normalized : normalized.substring(0, MAX_LENGTH);
    }
}
