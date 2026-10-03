package de.joinside.evmap_service.availability.mobilithek;

import de.joinside.evmap_service.mobilithek.Datex;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How much of the Mobilithek the stored inventory can resolve, per feed and in total. A diagnostic, never a filter:
 * the exact EVSE-ID join is unchanged (ADR 0015), this only counts what it will find.
 * <p>
 * The point is to tell two causes of a missing live status apart for every operator at once: the operator's EVSE-IDs
 * are missing from our master data (few stored ids with the feed's operator prefixes), or the feed lacks the charge
 * point (many stored, few matched). EVSE-IDs are public infrastructure identifiers, not personal data, so samples
 * may be logged.
 */
final class CoverageReport {
    /** Unmatched ids named per feed, enough to spot a spelling mismatch without flooding the log. */
    static final int SAMPLES = 3;
    /** Operator prefixes named per feed; platform feeds relay dozens of operators. */
    static final int PREFIXES_SHOWN = 5;
    /** Country code plus the three-character operator id: {@code DEEWE} of {@code DE*EWE*E000501S04*01}. */
    private static final int PREFIX_LENGTH = 5;

    /**
     * @param live               charge points the feed currently serves
     * @param matched            of them, how many match a stored EVSE-ID
     * @param untranslated       of them, how many are internal ids no static feed translated
     * @param prefixes           the feed's most frequent operator prefixes, most frequent first
     * @param otherPrefixes      how many further prefixes the feed carries
     * @param storedWithPrefixes stored EVSE-IDs carrying any of the feed's prefixes — what the feed could match at most
     * @param unmatchedSamples   EVSE-shaped live ids nothing stored matches, sorted
     */
    record Feed(String publisher, int live, int matched, int untranslated, List<String> prefixes, int otherPrefixes,
                long storedWithPrefixes, List<String> unmatchedSamples) {

        /** The prefixes as the log names them: {@code [DEAAA, DEBBB] (+3 more)}. */
        String prefixesText() {
            return otherPrefixes == 0 ? prefixes.toString() : prefixes + " (+" + otherPrefixes + " more)";
        }
    }

    /**
     * @param chargePoints    stored charge points in the provider's countries
     * @param storedEvseIds   distinct stored EVSE-IDs among them
     * @param liveEvseIds     distinct EVSE-IDs all feeds serve together
     * @param matchedEvseIds  stored EVSE-IDs with a live status from at least one feed
     */
    record Total(long chargePoints, int storedEvseIds, int liveEvseIds, int matchedEvseIds) {
    }

    private CoverageReport() {
    }

    /** The operator prefix of a normalized EVSE-ID, or {@code null} for an id that is not one. */
    static String prefixOf(String normalizedId) {
        return Datex.isEvseShaped(normalizedId) ? normalizedId.substring(0, PREFIX_LENGTH) : null;
    }

    /** How many stored EVSE-IDs carry each operator prefix. */
    static Map<String, Long> byPrefix(Collection<String> storedEvseIds) {
        Map<String, Long> counts = new HashMap<>();
        for (String evseId : storedEvseIds) {
            String prefix = prefixOf(evseId);
            if (prefix != null) counts.merge(prefix, 1L, Long::sum);
        }
        return counts;
    }

    static Feed forFeed(String publisher, Set<String> live, Set<String> stored, Map<String, Long> storedByPrefix) {
        int matched = 0;
        int untranslated = 0;
        Map<String, Integer> livePrefixes = new HashMap<>();
        List<String> unmatched = new ArrayList<>();
        for (String id : live) {
            String prefix = prefixOf(id);
            if (prefix == null) {
                untranslated++;
                continue;
            }
            livePrefixes.merge(prefix, 1, Integer::sum);
            if (stored.contains(id)) matched++;
            else unmatched.add(id);
        }
        List<String> prefixes = livePrefixes.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .toList();
        long storedWithPrefixes = prefixes.stream().mapToLong(prefix -> storedByPrefix.getOrDefault(prefix, 0L)).sum();
        return new Feed(publisher, live.size(), matched, untranslated,
                prefixes.subList(0, Math.min(PREFIXES_SHOWN, prefixes.size())),
                Math.max(0, prefixes.size() - PREFIXES_SHOWN), storedWithPrefixes,
                unmatched.stream().sorted(Comparator.naturalOrder()).limit(SAMPLES).toList());
    }

    static Total total(long chargePoints, Set<String> stored, Collection<Set<String>> liveByFeed) {
        Set<String> live = new HashSet<>();
        liveByFeed.forEach(live::addAll);
        int matched = 0;
        for (String id : live) if (stored.contains(id)) matched++;
        return new Total(chargePoints, stored.size(), live.size(), matched);
    }
}
