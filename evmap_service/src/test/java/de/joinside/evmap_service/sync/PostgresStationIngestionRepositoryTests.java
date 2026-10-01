package de.joinside.evmap_service.sync;

import de.joinside.evmap_service.support.PostgisDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The write side of master data against a real PostGIS: dedup by source id and by distance, the
 * per-country authority tie-break, the charge point inventory, run bookkeeping and incremental watermarks.
 */
class PostgresStationIngestionRepositoryTests {
    private JdbcClient jdbc;
    private PostgresStationIngestionRepository repository;

    @BeforeEach
    void setUp() {
        var dataSource = PostgisDatabase.dataSource();
        PostgisDatabase.clearMasterData();
        jdbc = PostgisDatabase.jdbc();
        repository = new PostgresStationIngestionRepository(jdbc, new DataSourceTransactionManager(dataSource), 2,
                new SourceAuthority(Map.of("DE", "BNetzA", "CH", "DIEMO")));
    }

    private static SourceStation station(String source, String id, String country, double latitude, double longitude,
                                         String name, List<SourceStation.SourceConnector> connectors,
                                         List<SourceStation.SourceChargePoint> chargePoints) {
        return new SourceStation(source, id, name, "Hauptstraße 1", "Stuttgart", "70173", country, "EnBW",
                latitude, longitude, "OPERATIONAL", Instant.parse("2026-09-01T00:00:00Z"), connectors, chargePoints);
    }

    private static SourceStation.SourceConnector type2(int quantity) {
        return new SourceStation.SourceConnector("TYPE_2", new BigDecimal("22.0"), quantity);
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    @Test
    @DisplayName("creates a station with its charge points, EVSE-IDs and connectors")
    void createsWithInventory() {
        var chargePoint = new SourceStation.SourceChargePoint("1010338*1", "DE*EBW*E912316*1", List.of(type2(1)));
        var result = repository.upsert(Stream.of(station("BNetzA", "1010338", "DE", 48.7758, 9.1829,
                "Stuttgart Mitte", List.of(), List.of(chargePoint))));

        assertThat(result).isEqualTo(new StationIngestionPort.IngestionResult(1, 1, 0, 0, 0));
        assertThat(count("master.charging_station")).isOne();
        Map<String, Object> stored = jdbc.sql("SELECT evse_id, evse_id_normalized FROM master.charge_point")
                .query().singleRow();
        assertThat(stored).containsEntry("evse_id", "DE*EBW*E912316*1")
                .containsEntry("evse_id_normalized", "DEEBWE9123161");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM master.charging_connector WHERE charge_point_id IS NOT NULL")
                .query(Integer.class).single()).isOne();
    }

    @Test
    @DisplayName("stores the operator and the price of a charge point, and replaces both with the inventory")
    void storesOperatorAndPricePerChargePoint() {
        var price = new SourceStation.SourcePrice("EUR", new BigDecimal("0.371"), new BigDecimal("1.5"), null,
                false, true, Instant.parse("2026-09-01T00:00:00Z"));
        var priced = new SourceStation.SourceChargePoint("FRS01E1", "FRS01E1", List.of(type2(1)), "Izivia", price);
        var unpriced = new SourceStation.SourceChargePoint("FRS01E2", "FRS01E2", List.of(type2(1)));
        var stationsOwn = new SourceStation.SourceChargePoint("FRS01E3", "FRS01E3", List.of(type2(1)), " enbw", null);
        repository.upsert(Stream.of(station("IRVE", "FRS01", "FR", 48.8566, 2.3522, "Paris", List.of(),
                List.of(priced, unpriced, stationsOwn))));

        // Stored only where it differs from the station's operator ("EnBW"), so the column stays sparse.
        assertThat(jdbc.sql("SELECT operator_name FROM master.charge_point ORDER BY evse_id").query(String.class).list())
                .containsExactly("Izivia", null, null);
        Map<String, Object> stored = jdbc.sql("SELECT currency, energy_per_kwh, session_fee, time_fee_per_minute, free, "
                + "further_fees FROM master.charge_point_price").query().singleRow();
        assertThat(stored).containsEntry("currency", "EUR").containsEntry("free", false)
                .containsEntry("further_fees", true).containsEntry("time_fee_per_minute", null);
        assertThat((BigDecimal) stored.get("energy_per_kwh")).isEqualByComparingTo("0.371");
        assertThat((BigDecimal) stored.get("session_fee")).isEqualByComparingTo("1.5");

        repository.upsert(Stream.of(station("IRVE", "FRS01", "FR", 48.8566, 2.3522, "Paris", List.of(),
                List.of(unpriced))));
        assertThat(count("master.charge_point_price")).isZero();
    }

    @Test
    @DisplayName("re-ingesting a source's station updates it in place and replaces its inventory")
    void updatesBySourceId() {
        repository.upsert(Stream.of(station("BNetzA", "1", "DE", 48.0, 9.0, "Old name", List.of(type2(2)), List.of())));
        var result = repository.upsert(Stream.of(station("BNetzA", "1", "DE", 48.0, 9.0, "New name", List.of(type2(4)), List.of())));

        assertThat(result.updated()).isOne();
        assertThat(count("master.charging_station")).isOne();
        assertThat(jdbc.sql("SELECT display_name FROM master.charging_station").query(String.class).single())
                .isEqualTo("New name");
        // Delete-and-insert: the old connector row does not linger next to the new one.
        assertThat(jdbc.sql("SELECT quantity FROM master.charging_connector").query(Integer.class).list())
                .containsExactly(4);
    }

    @Test
    @DisplayName("a second source within 30 m joins the existing station, and BNetzA keeps German fields")
    void dedupesByDistanceAndKeepsBnetzaFields() {
        repository.upsert(Stream.of(station("BNetzA", "1", "DE", 48.77580, 9.18290, "BNetzA name", List.of(type2(1)), List.of())));
        // ~10 m away: the same site as reported by the community source.
        var result = repository.upsert(Stream.of(station("OCM", "OCM-9", "DE", 48.77585, 9.18295, "OCM name", List.of(type2(3)), List.of())));

        assertThat(result.unchanged()).isOne();
        assertThat(count("master.charging_station")).isOne();
        assertThat(count("master.station_source")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT display_name FROM master.charging_station").query(String.class).single())
                .isEqualTo("BNetzA name");
        // The inventory follows the tie-break too: OCM's connectors do not replace BNetzA's.
        assertThat(jdbc.sql("SELECT quantity FROM master.charging_connector").query(Integer.class).list())
                .containsExactly(1);
    }

    @Test
    @DisplayName("in a country without an authority the most recent source wins the tie")
    void updatesForeignStationsFromAnySource() {
        repository.upsert(Stream.of(station("IRVE", "FR-1", "FR", 48.8566, 2.3522, "IRVE name", List.of(), List.of())));
        var result = repository.upsert(Stream.of(station("OCM", "OCM-1", "FR", 48.8566, 2.3522, "OCM name", List.of(), List.of())));

        assertThat(result.updated()).isOne();
        assertThat(jdbc.sql("SELECT display_name FROM master.charging_station").query(String.class).single())
                .isEqualTo("OCM name");
    }

    private static SourceStation.SourceChargePoint evse(String id) {
        return new SourceStation.SourceChargePoint(id, id, List.of(type2(1)));
    }

    private String displayName() {
        return jdbc.sql("SELECT display_name FROM master.charging_station").query(String.class).single();
    }

    private List<String> evseIds() {
        return jdbc.sql("SELECT evse_id FROM master.charge_point ORDER BY evse_id").query(String.class).list();
    }

    @Test
    @DisplayName("the authority takes over a station another source created first")
    void authorityTakesOver() {
        repository.upsert(Stream.of(station("OCM", "OCM-7", "CH", 47.3769, 8.5417, "OCM name", List.of(type2(3)), List.of())));
        var result = repository.upsert(Stream.of(station("DIEMO", "47.37690,8.54170", "CH", 47.3769, 8.5417,
                "DIEMO name", List.of(), List.of(evse("CH*ABC*E1"), evse("CH*ABC*E2")))));

        assertThat(result.updated()).isOne();
        assertThat(count("master.charging_station")).isOne();
        assertThat(displayName()).isEqualTo("DIEMO name");
        assertThat(evseIds()).containsExactly("CH*ABC*E1", "CH*ABC*E2");
        // OCM's station-level totals were replaced by the register's per-charge-point inventory.
        assertThat(jdbc.sql("SELECT COUNT(*) FROM master.charging_connector WHERE charge_point_id IS NULL")
                .query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("a source that is not the authority is only linked once the authority has claimed the station")
    void claimedStationIsNotOverwritten() {
        repository.upsert(Stream.of(station("DIEMO", "47.37690,8.54170", "CH", 47.3769, 8.5417,
                "DIEMO name", List.of(), List.of(evse("CH*ABC*E1")))));

        // The order of adapters is undefined, so OCM running after the register is the case that counts.
        var result = repository.upsert(Stream.of(station("OCM", "OCM-7", "CH", 47.37692, 8.54172, "OCM name",
                List.of(type2(3)), List.of())));

        assertThat(result.unchanged()).isOne();
        assertThat(displayName()).isEqualTo("DIEMO name");
        assertThat(evseIds()).containsExactly("CH*ABC*E1");
        assertThat(count("master.station_source")).isEqualTo(2);

        // And again on the next day: still the register's data, not a flip back and forth.
        repository.upsert(Stream.of(station("OCM", "OCM-7", "CH", 47.37692, 8.54172, "OCM renamed",
                List.of(type2(9)), List.of())));
        assertThat(displayName()).isEqualTo("DIEMO name");
        assertThat(evseIds()).containsExactly("CH*ABC*E1");
    }

    @Test
    @DisplayName("a source that is not the authority still maintains the stations the authority has not claimed")
    void unclaimedStationsAreStillUpdated() {
        repository.upsert(Stream.of(station("OCM", "OCM-8", "CH", 46.9480, 7.4474, "OCM old", List.of(type2(1)), List.of())));
        var result = repository.upsert(Stream.of(station("OCM", "OCM-8", "CH", 46.9480, 7.4474, "OCM new", List.of(type2(2)), List.of())));

        assertThat(result.updated()).isOne();
        assertThat(displayName()).isEqualTo("OCM new");
    }

    @Test
    @DisplayName("fields longer than their column are clipped rather than failing the station")
    void clipsOversizedFields() {
        var oversized = new SourceStation("OCM", "OCM-2", "Name", "Street", "City",
                "a-forty-character-community-postcode-xx", "DEU", "Operator", 50.0, 8.0, null,
                Instant.parse("2026-09-01T00:00:00Z"), List.of());

        var result = repository.upsert(Stream.of(oversized));

        assertThat(result.created()).isOne();
        assertThat(jdbc.sql("SELECT country_code FROM master.charging_station").query(String.class).single())
                .isEqualTo("DE");
    }

    @Test
    @DisplayName("records a run from start to finish, truncating an oversized error message")
    void recordsRuns() {
        UUID runId = repository.startRun();
        assertThat(jdbc.sql("SELECT status FROM master.sync_run WHERE id=:id").param("id", runId)
                .query(String.class).single()).isEqualTo("RUNNING");

        repository.finishRun(runId, StationIngestionPort.RunStatus.PARTIAL,
                new StationIngestionPort.IngestionResult(10, 7, 2, 0, 1), "x".repeat(5_000));

        Map<String, Object> run = jdbc.sql("SELECT status, processed, failed, length(error_message) AS error_length "
                + "FROM master.sync_run WHERE id=:id").param("id", runId).query().singleRow();
        assertThat(run).containsEntry("status", "PARTIAL").containsEntry("processed", 10).containsEntry("failed", 1)
                .containsEntry("error_length", 2000);

        UUID skipped = repository.startRun();
        repository.finishRun(skipped, StationIngestionPort.RunStatus.SKIPPED, null, null);
        assertThat(jdbc.sql("SELECT processed FROM master.sync_run WHERE id=:id").param("id", skipped)
                .query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("stores and advances an incremental watermark per source and scope")
    void storesWatermarks() {
        assertThat(repository.watermark("OCM", "DE")).isEmpty();

        repository.recordWatermark("OCM", "DE", Instant.parse("2026-09-01T00:00:00Z"));
        repository.recordWatermark("OCM", "DE", Instant.parse("2026-09-02T00:00:00Z"));
        repository.recordWatermark("OCM", "FR", Instant.parse("2026-08-01T00:00:00Z"));

        assertThat(repository.watermark("OCM", "DE")).contains(Instant.parse("2026-09-02T00:00:00Z"));
        assertThat(repository.watermark("OCM", "FR")).contains(Instant.parse("2026-08-01T00:00:00Z"));
    }
}
