package de.joinside.evmap_service.availability;

import java.time.Instant;

/**
 * One charge point's live state, as a provider reports it.
 *
 * @param evseId      the <em>normalized</em> EVSE-ID (see
 *                    {@link de.joinside.evmap_service.sync.EvseIds}). Providers normalize before
 *                    emitting, so the service never compares a raw identifier to a stored one.
 * @param status      one of {@link LiveAvailability}.
 * @param observedAt  when the source last changed this status. Carried all the way to the client,
 *                    which renders the age rather than the value alone — a status without its
 *                    timestamp is indistinguishable from a stale one presented as current.
 * @param attribution who must be credited for this one entry, or {@code null} for the provider's own
 *                    {@link AvailabilityProvider#attribution()}. Set by providers that relay several
 *                    publishers under different licences — the Mobilithek brokers one feed per operator,
 *                    and CC BY requires naming the operator, not the platform.
 */
public record ChargePointAvailability(String evseId, String status, Instant observedAt, Attribution attribution) {

    /** An entry credited to the provider that reported it. */
    public ChargePointAvailability(String evseId, String status, Instant observedAt) {
        this(evseId, status, observedAt, null);
    }
}
