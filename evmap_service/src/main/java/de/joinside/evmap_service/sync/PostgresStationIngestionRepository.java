package de.joinside.evmap_service.sync;

import de.joinside.evmap_service.logging.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@Repository
@ConditionalOnProperty(name = "evmap.sync.enabled", havingValue = "true")
@EnableConfigurationProperties(SourceAuthority.class)
class PostgresStationIngestionRepository implements StationIngestionPort, SyncStateStore {
    private static final Logger log = LoggerFactory.getLogger(PostgresStationIngestionRepository.class);

    // Named parameters shared by several statements; the SQL refers to them as :source, :sourceId, ….
    private static final String PARAM_SOURCE = "source";
    private static final String PARAM_SOURCE_ID = "sourceId";
    private static final String PARAM_STATION_ID = "stationId";
    private static final String PARAM_COUNTRY = "country";
    private static final String PARAM_LATITUDE = "latitude";
    private static final String PARAM_LONGITUDE = "longitude";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final int batchSize;
    private final SourceAuthority authority;

    PostgresStationIngestionRepository(JdbcClient jdbc,
                                       PlatformTransactionManager transactionManager,
                                       @Value("${evmap.sync.batch-size:1000}") int batchSize,
                                       SourceAuthority authority) {
        this.jdbc = jdbc;
        this.authority = authority;
        // A TransactionTemplate rather than @Transactional on a helper method: batches are committed
        // from inside this class, and a self-invocation never passes through the transactional proxy.
        this.transactions = new TransactionTemplate(transactionManager);
        this.batchSize = Math.max(1, batchSize);
    }

    /**
     * Ingests in batches, each its own transaction.
     * <p>
     * One transaction around the whole run was the original shape, and it does not survive real data:
     * the first run against an empty database is both the longest and the most insert-heavy, so it is
     * the run most likely to meet a bad upstream record — and a single one of those would roll back
     * every station ingested in the minutes before it, leaving an empty database. Batching bounds
     * that blast radius, and because ingestion is an upsert, whatever a truncated run left behind is
     * simply completed by the next one. See ADR 0007.
     */
    @Override
    public IngestionResult upsert(Stream<SourceStation> stations) {
        return BatchedIngestion.run(stations, batchSize,
                work -> transactions.executeWithoutResult(status -> work.run()),
                this::upsertOne);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID startRun() {
        UUID runId = UUID.randomUUID();
        jdbc.sql("INSERT INTO master.sync_run (id, started_at, status) VALUES (:id, now(), 'RUNNING')")
                .param("id", runId)
                .update();
        return runId;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishRun(UUID runId, RunStatus status, IngestionResult result, String errorMessage) {
        IngestionResult counts = result == null ? IngestionResult.none() : result;
        jdbc.sql("UPDATE master.sync_run SET finished_at=now(), status=:status, processed=:processed, " +
                        "created=:created, updated=:updated, unchanged=:unchanged, failed=:failed, " +
                        "error_message=:error WHERE id=:id")
                .param("id", runId)
                .param("status", status.name())
                .param("processed", counts.processed())
                .param("created", counts.created())
                .param("updated", counts.updated())
                .param("unchanged", counts.unchanged())
                .param("failed", counts.failed())
                // Truncated to the column width: a driver stack trace must not fail the bookkeeping write.
                .param("error", errorMessage == null ? null : errorMessage.substring(0, Math.min(errorMessage.length(), 2000)))
                .update();
    }

    @Override
    public Optional<Instant> watermark(String source, String scope) {
        return jdbc.sql("SELECT watermark FROM master.source_sync_state WHERE source=:source AND scope=:scope")
                .param(PARAM_SOURCE, source)
                .param("scope", scope)
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordWatermark(String source, String scope, Instant watermark) {
        jdbc.sql("INSERT INTO master.source_sync_state (source, scope, watermark, updated_at) " +
                        "VALUES (:source,:scope,:watermark, now()) " +
                        "ON CONFLICT (source, scope) " +
                        "DO UPDATE SET watermark=EXCLUDED.watermark, updated_at=EXCLUDED.updated_at")
                .param(PARAM_SOURCE, source)
                .param("scope", scope)
                .param("watermark", timestamp(watermark))
                .update();
    }

    /**
     * The PostgreSQL JDBC driver cannot infer a SQL type for {@link Instant} and fails with SQL state
     * 07006 — client-side, before the statement is ever sent, which Spring surfaces as a
     * {@code BadSqlGrammarException} that never appears in the server log. Every timestamp bound to a
     * statement has to be converted here.
     */
    private static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * Clips a value to its column width. Community-maintained sources put arbitrary text in fields
     * that are nominally short — a 40-character "postcode" from Open Charge Map is enough to fail the
     * insert — and losing the tail of one field is better than losing the station.
     */
    private static String clip(String value, int maxLength) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private BatchedIngestion.Outcome upsertOne(SourceStation source) {
        UUID stationId = jdbc.sql("SELECT station_id " +
                        "FROM master.station_source " +
                        "WHERE source=:source AND source_station_id=:sourceId")
                .param(PARAM_SOURCE, source.source())
                .param(PARAM_SOURCE_ID, source.sourceStationId())
                .query(UUID.class).optional().orElseGet(() -> nearby(source));

        BatchedIngestion.Outcome outcome;
        if (stationId == null) {
            stationId = UUID.randomUUID();
            jdbc.sql("INSERT INTO master.charging_station " +
                            "(id, display_name, street, city, postal_code, country_code, operator_name, " +
                            "latitude, longitude, availability_status) " +
                            "VALUES (:id,:name,:street,:city,:postal,:country,:operator,:latitude,:longitude,:availability)")
                    .param("id", stationId)
                    .param("name", clip(source.name(), 500))
                    .param("street", clip(source.street(), 500))
                    .param("city", clip(source.city(), 200))
                    .param("postal", clip(source.postalCode(), 32))
                    .param(PARAM_COUNTRY, clip(source.countryCode(), 2))
                    .param("operator", clip(source.operatorName(), 500))
                    .param(PARAM_LATITUDE, source.latitude())
                    .param(PARAM_LONGITUDE, source.longitude())
                    .param("availability", clip(source.availabilityStatus(), 32))
                    .update();
            outcome = BatchedIngestion.Outcome.CREATED;
            log.debug("Created station {} from {}/{}", stationId, source.source(), source.sourceStationId());
        } else if (mayUpdate(stationId, source)) {
            jdbc.sql("UPDATE master.charging_station " +
                            "SET display_name=:name, street=:street, city=:city, " +
                            "postal_code=:postal, country_code=:country, operator_name=:operator, " +
                            "latitude=:latitude, longitude=:longitude, availability_status=:availability, " +
                            "updated_at=now() WHERE id=:id")
                    .param("id", stationId)
                    .param("name", clip(source.name(), 500))
                    .param("street", clip(source.street(), 500))
                    .param("city", clip(source.city(), 200))
                    .param("postal", clip(source.postalCode(), 32))
                    .param(PARAM_COUNTRY, clip(source.countryCode(), 2))
                    .param("operator", clip(source.operatorName(), 500))
                    .param(PARAM_LATITUDE, source.latitude())
                    .param(PARAM_LONGITUDE, source.longitude())
                    .param("availability", clip(source.availabilityStatus(), 32))
                    .update();
            outcome = BatchedIngestion.Outcome.UPDATED;
            log.debug("Updated station {} from {}/{}", stationId, source.source(), source.sourceStationId());
        } else {
            outcome = BatchedIngestion.Outcome.UNCHANGED;
            log.debug("Kept station {} unchanged, {} is not the authority its country gives way to",
                    stationId, source.source());
        }
        // Charge points and connectors follow the same tie-break as the station's own fields. Writing
        // them unconditionally would let whichever adapter ran last overwrite the inventory of a station
        // whose address and operator came from the other source, leaving the two halves describing
        // different sites — and would attach EVSE-IDs from one site to the coordinates of another.
        if (outcome != BatchedIngestion.Outcome.UNCHANGED) replaceInventory(stationId, source);

        jdbc.sql("INSERT INTO master.station_source " +
                        "(station_id, source, source_station_id, last_updated_at) " +
                        "VALUES (:stationId,:source,:sourceId,:updated) " +
                        "ON CONFLICT (source, source_station_id) " +
                        "DO UPDATE SET station_id=EXCLUDED.station_id, last_updated_at=EXCLUDED.last_updated_at")
                .param(PARAM_STATION_ID, stationId)
                .param(PARAM_SOURCE, clip(source.source(), 32))
                .param(PARAM_SOURCE_ID, clip(source.sourceStationId(), 255))
                .param("updated", timestamp(source.lastUpdatedAt()))
                .update();
        return outcome;
    }

    /**
     * Replaces a station's charge points and connectors with the ones the winning source reports.
     * <p>
     * Delete-and-insert rather than a per-row upsert: {@code master.charging_connector} has no natural
     * key to match on — a site can legitimately list the same type at the same rating twice — and a
     * source dropping a connector between runs has to remove the row, not leave a stale one behind.
     * <p>
     * Connectors are deleted before charge points even though the foreign key cascades, so that the
     * order does not depend on the cascade being there: the station-level connectors of a source that
     * reports no charge points are not covered by it.
     */
    private void replaceInventory(UUID stationId, SourceStation source) {
        jdbc.sql("DELETE FROM master.charging_connector WHERE station_id=:stationId")
                .param(PARAM_STATION_ID, stationId)
                .update();
        jdbc.sql("DELETE FROM master.charge_point WHERE station_id=:stationId")
                .param(PARAM_STATION_ID, stationId)
                .update();

        for (SourceStation.SourceChargePoint chargePoint : source.chargePoints()) {
            UUID chargePointId = UUID.randomUUID();
            jdbc.sql("INSERT INTO master.charge_point " +
                            "(id, station_id, source, source_charge_point_id, evse_id, evse_id_normalized, operator_name) " +
                            "VALUES (:id,:stationId,:source,:sourceId,:evseId,:evseNormalized,:operator)")
                    .param("id", chargePointId)
                    .param(PARAM_STATION_ID, stationId)
                    .param(PARAM_SOURCE, clip(source.source(), 32))
                    .param(PARAM_SOURCE_ID, clip(chargePoint.sourceChargePointId(), 255))
                    .param("evseId", clip(chargePoint.evseId(), 64))
                    .param("evseNormalized", EvseIds.normalize(chargePoint.evseId()))
                    .param("operator", clip(ownOperator(chargePoint, source), 500))
                    .update();
            if (chargePoint.price() != null) insertPrice(chargePointId, chargePoint.price());
            for (SourceStation.SourceConnector connector : chargePoint.connectors()) {
                insertConnector(stationId, chargePointId, connector);
            }
        }
        // Sources that only know totals keep hanging their connectors off the station itself.
        for (SourceStation.SourceConnector connector : source.connectors()) {
            insertConnector(stationId, null, connector);
        }
    }

    /**
     * The charge point's operator where it differs from the station's, else {@code null}. Kept sparse on purpose:
     * {@code NULL} means "the station's operator", the operator directory and filters only have to look at the
     * few charge points of bundled stations, and a station's own operator is not stored a thousand times over.
     */
    private static String ownOperator(SourceStation.SourceChargePoint chargePoint, SourceStation station) {
        String own = chargePoint.operatorName();
        if (own == null || own.isBlank()) return null;
        return own.trim().equalsIgnoreCase(station.operatorName() == null ? "" : station.operatorName().trim()) ? null : own.trim();
    }

    /** Removed with its charge point by the cascade, so replacing the inventory replaces the price. */
    private void insertPrice(UUID chargePointId, SourceStation.SourcePrice price) {
        jdbc.sql("INSERT INTO master.charge_point_price " +
                        "(charge_point_id, currency, energy_per_kwh, session_fee, time_fee_per_minute, free, " +
                        "further_fees, observed_at) " +
                        "VALUES (:id,:currency,:energy,:session,:time,:free,:further,:observed)")
                .param("id", chargePointId)
                .param("currency", price.currency())
                .param("energy", price.energyPerKwh())
                .param("session", price.sessionFee())
                .param("time", price.timeFeePerMinute())
                .param("free", price.free())
                .param("further", price.furtherFees())
                .param("observed", timestamp(price.observedAt()))
                .update();
    }

    private void insertConnector(UUID stationId, UUID chargePointId, SourceStation.SourceConnector connector) {
        jdbc.sql("INSERT INTO master.charging_connector " +
                        "(id, station_id, charge_point_id, connector_type, power_kw, quantity) " +
                        "VALUES (:id,:stationId,:chargePointId,:type,:power,:quantity)")
                .param("id", UUID.randomUUID())
                .param(PARAM_STATION_ID, stationId)
                .param("chargePointId", chargePointId)
                .param("type", clip(connector.connectorType(), 64))
                .param("power", connector.powerKw())
                .param("quantity", connector.quantity())
                .update();
    }

    private UUID nearby(SourceStation source) {
        return jdbc.sql("SELECT id FROM master.charging_station " +
                        "WHERE country_code=:country AND ST_DWithin(location, ST_SetSRID(ST_MakePoint(:longitude,:latitude),4326)::geography, 30) " +
                        "ORDER BY location <-> ST_SetSRID(ST_MakePoint(:longitude,:latitude),4326)::geography LIMIT 1")
                .param(PARAM_COUNTRY, clip(source.countryCode(), 2))
                .param(PARAM_LATITUDE, source.latitude())
                .param(PARAM_LONGITUDE, source.longitude())
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    /**
     * Whether {@code source} may rewrite this existing station's fields and inventory.
     * <p>
     * The country's authority always may — that is how it takes over a station another source created
     * first. Any other source may only while the authority has not claimed the station, i.e. has no
     * {@code station_source} row for it; from then on it is merely linked. A country without an
     * authority has no such rule and the most recent source wins.
     * <p>
     * The country is the station's stored one, not the record's: a station matched across sources
     * keeps the country it was created with.
     */
    private boolean mayUpdate(UUID stationId, SourceStation source) {
        String country = jdbc.sql("SELECT country_code FROM master.charging_station WHERE id=:id")
                .param("id", stationId)
                .query(String.class).single();
        return authority.sourceFor(country)
                .map(authoritative -> authoritative.equals(source.source()) || !claimedBy(stationId, authoritative))
                .orElse(true);
    }

    private boolean claimedBy(UUID stationId, String source) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM master.station_source WHERE station_id=:stationId AND source=:source)")
                .param(PARAM_STATION_ID, stationId)
                .param(PARAM_SOURCE, source)
                .query(Boolean.class).single();
    }
}
