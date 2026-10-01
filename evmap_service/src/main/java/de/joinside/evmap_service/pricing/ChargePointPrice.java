package de.joinside.evmap_service.pricing;

/**
 * One charge point's price, as a provider reports it.
 *
 * @param evseId the <em>normalized</em> EVSE-ID (see {@link de.joinside.evmap_service.sync.EvseIds}); providers
 *               normalize before emitting, so the service never compares a raw identifier to a stored one
 * @param price  the price, already gross and checked; a provider emits nothing rather than an uncertain price
 */
public record ChargePointPrice(String evseId, AdHocPrice price) {
}
