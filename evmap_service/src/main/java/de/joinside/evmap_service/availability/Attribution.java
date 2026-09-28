package de.joinside.evmap_service.availability;

/**
 * How a live source must be credited where its data is shown.
 * <p>
 * Every provider's licence so far — dl-de/by-2.0, Licence Ouverte — requires naming the source, and
 * Lastenheft §5 asks for provenance per charge point. Unlike {@link AvailabilityProvider#source()},
 * which is a log token, this is shown to users, so the values are the publisher's own spelling.
 * They are proper names and licence titles rather than prose, and are therefore not translated; the
 * client supplies the surrounding words through its own i18n resources.
 *
 * @param name    the publisher as it asks to be credited, e.g. {@code MobiData BW}
 * @param licence the licence title, e.g. {@code Datenlizenz Deutschland – Namensnennung – 2.0}
 * @param url     where the data and its licence are described
 */
public record Attribution(String name, String licence, String url) {
}
