package de.joinside.evmap_service.availability;

import java.time.Instant;

/**
 * One charge point's live state, as a provider reports it.
 *
 * @param evseId     the <em>normalized</em> EVSE-ID (see
 *                   {@link de.joinside.evmap_service.sync.EvseIds}). Providers normalize before
 *                   emitting, so the service never compares a raw identifier to a stored one.
 * @param status     one of {@link LiveAvailability}.
 * @param observedAt when the source last changed this status. Carried all the way to the client,
 *                   which renders the age rather than the value alone — a status without its
 *                   timestamp is indistinguishable from a stale one presented as current.
 */
public record ChargePointAvailability(String evseId, String status, Instant observedAt) {
}
