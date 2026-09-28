package de.joinside.evmap_service.availability;

import java.util.List;

/**
 * One live-status source, normalized onto {@link ChargePointAvailability}.
 * <p>
 * The counterpart to {@link de.joinside.evmap_service.sync.SourceAdapter}, and shaped like it on
 * purpose: implementations live in their own sub-package and know nothing about each other, about
 * the stations they describe, or about the request they take part in. See the package documentation.
 */
public interface AvailabilityProvider {

    /**
     * Stable token identifying this source. Tagged onto log lines and reported per provider in the
     * service's diagnostics; never shown to users — what they see is {@link #attribution()}.
     */
    String source();

    /**
     * How this source is credited next to its data in the app. Required rather than defaulted: every
     * live source so far is licensed on condition of attribution, so a provider without one would
     * ship data the app is not allowed to show uncredited.
     */
    Attribution attribution();

    /**
     * Whether this provider takes part at all. Checked centrally so a disabled provider is reported
     * once, in one place.
     * <p>
     * A provider that is enabled but cannot answer today — a missing API key, an exhausted daily
     * budget — is not this: it belongs in {@link #fetch(GeoBounds)}, which may return an empty list
     * rather than failing. Being over a free quota is a healthy state, not an incident.
     */
    default boolean enabled() {
        return true;
    }

    /**
     * Whether this provider claims to know anything about a country, as an ISO 3166-1 alpha-2 code.
     * <p>
     * This is what keeps the Europe-wide fallback from being consulted where a national access point
     * already answers, and what stops a regional provider from being asked about the other end of the
     * continent. It is a claim about scope, not a guarantee of coverage: a provider that covers a
     * country still answers nothing for the charge points in it that publish no EVSE-ID.
     */
    boolean covers(String countryCode);

    /**
     * Live status for every charge point this provider knows inside {@code bounds}.
     * <p>
     * The box is a ceiling on what is needed, not a filter the result must honour: a source that
     * cannot be asked by area — a national file without coordinates — may answer with everything it
     * has, and the service keeps only the identifiers it asked for.
     * <p>
     * Returns an empty list rather than throwing when the source is unreachable, malformed or out of
     * budget: one live source being down must degrade that area to "unknown", never fail the station
     * or the map around it. Exceptions escaping here are treated as bugs in the provider, contained
     * by {@link AvailabilityService}, and logged as such.
     * <p>
     * Identifiers in the result are already normalized through
     * {@link de.joinside.evmap_service.sync.EvseIds}.
     */
    List<ChargePointAvailability> fetch(GeoBounds bounds);
}
