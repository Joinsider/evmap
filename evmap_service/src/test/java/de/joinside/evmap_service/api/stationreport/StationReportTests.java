package de.joinside.evmap_service.api.stationreport;

import de.joinside.evmap_service.api.station.StationController.StationNotFoundException;
import de.joinside.evmap_service.support.PostgisDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Station reports and the admin queue against the real schema (ADR 0021). */
class StationReportTests {
    private StationReportService reports;
    private UUID station;
    private UUID other;
    private UUID me;
    private UUID second;
    private UUID admin;

    @BeforeEach
    void setUp() {
        PostgisDatabase.clearMasterData();
        PostgisDatabase.clearUserData();
        reports = new StationReportService(new StationReportRepository(PostgisDatabase.jdbc()));
        station = PostgisDatabase.insertStation("EnBW Stuttgart", "EnBW", "DE", 48.77, 9.18);
        other = PostgisDatabase.insertStation("Ionity", "Ionity", "DE", 48.8, 9.2);
        me = account();
        second = account();
        admin = account();
    }

    private static UUID account() {
        UUID id = UUID.randomUUID();
        PostgisDatabase.jdbc().sql("INSERT INTO user_data.account (id) VALUES (:id)").param("id", id).update();
        return id;
    }

    private static long count(String where) {
        return PostgisDatabase.jdbc().sql("SELECT count(*) FROM user_data.station_report WHERE " + where).query(Long.class).single();
    }

    @Test
    @DisplayName("a report is kept once per reporter, station and reason while it is open")
    void openReportIsIdempotent() {
        reports.report(station, me, "defective", "Plug 2 is dead");
        reports.report(station, me, "defective", "Still dead");
        reports.report(station, me, "gone", null);

        assertThat(count("true")).isEqualTo(2);
        assertThat(count("note = 'Plug 2 is dead'")).isEqualTo(1);
    }

    @Test
    @DisplayName("a closed report does not block reporting the same thing again")
    void closedReportDoesNotBlock() {
        reports.report(station, me, "defective", null);
        reports.close(station, "defective", admin, "resolved");
        reports.report(station, me, "defective", null);

        assertThat(count("status = 'open'")).isEqualTo(1);
        assertThat(count("status = 'resolved'")).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown reason, an over-long note or an unknown station is refused, a blank note is stored as none")
    void validation() {
        assertThatThrownBy(() -> reports.report(station, me, "rude", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reports.report(station, me, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reports.report(station, me, "other", "x".repeat(StationReportService.MAX_NOTE_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reports.report(UUID.randomUUID(), me, "gone", null)).isInstanceOf(StationNotFoundException.class);
        assertThat(count("true")).isZero();

        reports.report(station, me, "other", "   ");
        reports.report(station, second, "other", "x".repeat(StationReportService.MAX_NOTE_LENGTH));
        assertThat(count("note IS NULL")).isEqualTo(1);
        assertThat(count("true")).isEqualTo(2);
    }

    @Test
    @DisplayName("the queue groups open reports per station and reason with the count and the latest notes, newest first")
    void queueGroups() {
        reports.report(other, me, "gone", null);
        reports.report(station, me, "wrong_power", "It is 11 kW");
        reports.report(station, second, "wrong_power", "Only 11 kW here");
        reports.report(station, second, "defective", "  ");

        var queue = reports.openGroups();

        assertThat(queue).hasSize(3);
        var power = queue.stream().filter(line -> line.reason().equals("wrong_power")).findFirst().orElseThrow();
        assertThat(power.stationId()).isEqualTo(station);
        assertThat(power.stationName()).isEqualTo("EnBW Stuttgart");
        assertThat(power.operatorName()).isEqualTo("EnBW");
        assertThat(power.count()).isEqualTo(2);
        assertThat(power.notes()).containsExactly("Only 11 kW here", "It is 11 kW");
        assertThat(queue.stream().filter(line -> line.reason().equals("defective")).findFirst().orElseThrow().notes()).isEmpty();
        assertThat(queue.getLast().stationId()).isEqualTo(other);
    }

    @Test
    @DisplayName("at most five notes are shown per line, but every report is counted")
    void notesAreBounded() {
        for (int i = 0; i < 7; i++) reports.report(station, account(), "other", "note " + i);

        var line = reports.openGroups().getFirst();

        assertThat(line.count()).isEqualTo(7);
        assertThat(line.notes()).hasSize(5).first().isEqualTo("note 6");
    }

    @Test
    @DisplayName("resolving or dismissing closes the line, leaves master data alone and cannot be done twice")
    void closing() {
        reports.report(station, me, "gone", null);
        reports.report(station, second, "gone", null);
        reports.report(station, me, "defective", null);

        reports.close(station, "gone", admin, "resolved");
        reports.close(station, "defective", admin, "dismissed");

        assertThat(reports.openGroups()).isEmpty();
        assertThat(count("status = 'resolved'")).isEqualTo(2);
        assertThat(count("status = 'dismissed' AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL")).isEqualTo(1);
        assertThat(PostgisDatabase.jdbc().sql("SELECT count(*) FROM master.charging_station").query(Long.class).single()).isEqualTo(2);
        assertThatThrownBy(() -> reports.close(station, "gone", admin, "resolved")).isInstanceOf(StationNotFoundException.class);
        assertThatThrownBy(() -> reports.close(station, "rude", admin, "resolved")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("reports go with their reporter and with the station")
    void cascades() {
        reports.report(station, me, "gone", "mine");
        reports.report(other, second, "gone", "theirs");

        PostgisDatabase.jdbc().sql("DELETE FROM user_data.account WHERE id = :id").param("id", me).update();
        assertThat(count("true")).isEqualTo(1);

        PostgisDatabase.jdbc().sql("DELETE FROM master.charging_station WHERE id = :id").param("id", other).update();
        assertThat(count("true")).isZero();
    }
}
