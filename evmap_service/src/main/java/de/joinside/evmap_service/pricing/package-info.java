/**
 * What charging costs at a charge point: the ad-hoc price, read from the registers and live tariff feeds.
 *
 * <h2>Two kinds of price, one answer</h2>
 * <p>
 * Some registers publish a price with their static data (France's IRVE, as free text); {@code sync} reads it
 * where it is certain and stores it in {@code master.charge_point_price}. Germany's operators publish OCPI
 * tariffs through the national access points, which change at any time and are read here on demand, exactly
 * like live status (ADR 0015): never written to {@code master.*}, cached briefly, attached only on an exact
 * EVSE-ID match. {@link de.joinside.evmap_service.pricing.PricingService} merges both, the live tariff first.
 *
 * <h2>Layering</h2>
 * <p>
 * The counterpart to {@code availability}, shaped the same way:
 *
 * <pre>
 *   pricing.&lt;provider&gt;  ──uses──▶  pricing  ──reads──▶  master.charge_point (written by sync)
 *   (mobidata, …)
 * </pre>
 *
 * <ul>
 *   <li>{@link de.joinside.evmap_service.pricing.PriceProvider} is the only thing a tariff source implements,
 *       {@link de.joinside.evmap_service.pricing.ChargePointPrice} the only shape it may produce. Nothing in this
 *       package names a provider.</li>
 *   <li>It shares three value types and nothing else: {@link de.joinside.evmap_service.sync.EvseIds} (both
 *       sides of the join must normalize identically), and {@code availability.GeoBounds} and
 *       {@code availability.Attribution}, because the access points are asked by area and credited the same
 *       way. Neither {@code sync} nor {@code availability} references this package.</li>
 * </ul>
 *
 * <h2>The rule that shapes everything else</h2>
 * <p>
 * <strong>A price shown wrongly is worse than none</strong> (product owner, ADR 0022). A provider emits a price
 * only when its gross amount is established; everything else is no price, which the client renders as such.
 *
 * <h2>Adding a provider</h2>
 * <p>
 * One package under {@code pricing}, with an {@code XProperties} record ({@code evmap.pricing.x}), the parsing
 * separated from the HTTP code so it is testable against a fixture, and an {@code XPriceProvider} gated on
 * {@code evmap.pricing.enabled}. Then extend {@code PriceProviderRegistrationTests}.
 */
package de.joinside.evmap_service.pricing;
