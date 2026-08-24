/**
 * Live occupancy of individual charge points, read from national access points.
 *
 * <h2>What this is not</h2>
 * <p>
 * {@code master.charging_station.availability_status} — see
 * {@link de.joinside.evmap_service.sync.AvailabilityStatus} — is the <em>reported</em> service state
 * of the register that supplied a station, refreshed once per 24 h sync run. This package answers a
 * different question: is this charge point free <em>right now</em>. The two are shown together and
 * must not be confused in code either; nothing here writes to {@code master.*}.
 *
 * <h2>Layering</h2>
 * <p>
 * Mirrors {@code sync}, deliberately, so that the two axes read the same way:
 *
 * <pre>
 *   availability.&lt;provider&gt;  ──uses──▶  availability
 *   (mobidata, …)
 * </pre>
 *
 * <ul>
 *   <li>{@link de.joinside.evmap_service.availability.AvailabilityProvider} is the only thing a live
 *       source implements, and {@link de.joinside.evmap_service.availability.ChargePointAvailability}
 *       the only shape it may produce. Nothing in this package names a provider —
 *       {@link de.joinside.evmap_service.availability.AvailabilityService} takes whatever the
 *       component scan found, exactly as {@code SyncJob} does with adapters.</li>
 *   <li>No provider may reference another, and none may reference {@code sync} beyond
 *       {@link de.joinside.evmap_service.sync.EvseIds}, which is shared on purpose: both sides of the
 *       join must normalize identifiers identically.</li>
 *   <li>{@code sync} never references this package. The two axes meet at {@code master.charge_point},
 *       which {@code sync} writes and this package only reads.</li>
 * </ul>
 *
 * <h2>The rule that shapes everything else</h2>
 * <p>
 * <strong>Live status attaches to a charge point only on an exact EVSE-ID match.</strong> There is no
 * geographic, name-based or fuzzy resolution anywhere in this package, and adding one would be a
 * regression rather than an improvement in coverage: a station shown as free when it is occupied is
 * the one error users would drive to. Coverage is consequently partial — 14,5 % of live EVSEs
 * resolved when this was built — and the honest answer for the rest is
 * {@link de.joinside.evmap_service.availability.LiveAvailability#UNKNOWN}. See ADR 0015.
 *
 * <h2>Adding a provider</h2>
 * <p>
 * One package under {@code availability}, by convention named after the source:
 *
 * <ul>
 *   <li>{@code XProperties} — a {@code @ConfigurationProperties("evmap.availability.x")} record with
 *       an {@code enabled} flag and {@code @DefaultValue}s for everything.</li>
 *   <li>{@code XResponses} (or a parser) — the mapping onto {@code ChargePointAvailability},
 *       separated from the provider so it is testable against a fixture rather than the network.
 *       Status values go through {@link de.joinside.evmap_service.availability.LiveAvailability},
 *       a closed vocabulary the iOS client mirrors, so extending it is a two-sided change.</li>
 *   <li>{@code XAvailabilityProvider} — a {@code @Component} gated on
 *       {@code evmap.availability.enabled}, so the sync deployable never registers it.</li>
 * </ul>
 * <p>
 * Then extend {@code AvailabilityProviderRegistrationTests}, which asserts the full set for the same
 * reason {@code SourceAdapterRegistrationTests} does.
 */
package de.joinside.evmap_service.availability;
