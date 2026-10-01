package de.joinside.evmap_service.sync.support;

import java.util.Map;

/**
 * The few string rules the register parsers share: what counts as "no value", which of several values
 * wins, and which operator names a station.
 */
public final class SourceText {
    private SourceText() {
    }

    /** @return the trimmed value, or {@code null} for {@code null} and for blank text */
    public static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** @return the first argument that is not {@code null}, or {@code null} when all are */
    public static String firstPresent(String... values) {
        for (String value : values) if (value != null) return value;
        return null;
    }

    /**
     * @return the key with the highest count; the first of them in the map's iteration order on a tie,
     * so that an insertion-ordered map makes the tie-break "first seen"; {@code null} for an empty map
     */
    public static String mostFrequent(Map<String, Integer> counts) {
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }
}
