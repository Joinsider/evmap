/**
 * Ingestion of external charging-station data into sync-owned master data.
 *
 * <h2>Layering</h2>
 * <p>
 * This package owns the contracts; the sub-packages own the sources. The dependency direction is
 * strictly one way and worth keeping that way, because it is what lets a new country be added without
 * reading any of the existing adapters:
 *
 * <pre>
 *   sync.&lt;source&gt;  ──uses──▶  sync  ◀──uses──  sync.support
 *   (bnetza, ocm, irve, …)                        (helpers, no domain knowledge)
 * </pre>
 *
 * <ul>
 *   <li>{@link de.joinside.evmap_service.sync.SourceAdapter} is the only thing a source has to
 *       implement, and {@link de.joinside.evmap_service.sync.SourceStation} the only shape it may
 *       produce. Nothing in this package knows which sources exist — {@code SyncJob} takes whatever
 *       adapters the component scan found.</li>
 *   <li>{@link de.joinside.evmap_service.sync.StationIngestionPort} is the <em>sole</em> write boundary
 *       into master data. Adapters must never receive it: an adapter's job ends at producing records,
 *       and handing it the port would let a source decide how and when data is committed. Incremental
 *       sources get the deliberately narrow {@link de.joinside.evmap_service.sync.SyncStateStore}
 *       instead.</li>
 *   <li>No source package may reference another source package, and none may reference
 *       {@code api}. Master data is written here and read there; blurring that is what ADR 0001's
 *       service split would later have to undo.</li>
 * </ul>
 *
 * <h2>Adding a source</h2>
 * <p>
 * One package under {@code sync}, three types, by convention named after the source:
 *
 * <ul>
 *   <li>{@code XProperties} — a {@code @ConfigurationProperties("evmap.sync.x")} record with an
 *       {@code enabled} flag and {@code @DefaultValue}s for everything, so a container that configures
 *       nothing still runs.</li>
 *   <li>{@code XParser} (or the equivalent response records) — the mapping onto {@code SourceStation},
 *       separated from the adapter so it is testable against a fixture rather than against the network.
 *       Connector labels go through {@link de.joinside.evmap_service.sync.ConnectorTypes} and service
 *       state through {@link de.joinside.evmap_service.sync.AvailabilityStatus}; both are closed
 *       vocabularies mirrored by the iOS client, so extending either is a two-sided change.</li>
 *   <li>{@code XSourceAdapter} — a {@code @Component} annotated
 *       {@code @ConditionalOnProperty(name = "evmap.sync.enabled", havingValue = "true")}, so the API
 *       deployable never registers it and can never write master data.</li>
 * </ul>
 * <p>
 * Then extend {@code SourceAdapterRegistrationTests}, which asserts the full set of adapters and would
 * otherwise let a wiring mistake ship as a container that starts happily and ingests nothing.
 *
 * <h2>What a run guarantees</h2>
 * <p>
 * Sources are ingested one after another, each into its own batched ingestion (ADR 0007). A source that
 * fails is contained: it is recorded, the run continues with the remaining sources, and the run ends
 * {@code PARTIAL} rather than {@code FAILED}. Only a source that contributed every record it fetched
 * without a single ingestion failure gets its {@link
 * de.joinside.evmap_service.sync.SourceAdapter#commitProgress()} called. See ADR 0013.
 */
package de.joinside.evmap_service.sync;
