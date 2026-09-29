package de.joinside.evmap_service.api.admin;

import de.joinside.evmap_service.support.PostgisDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminRepositoryTests {
    private AdminRepository repository;

    @BeforeEach
    void setUp() {
        PostgisDatabase.clearMasterData();
        PostgisDatabase.clearUserData();
        repository = new AdminRepository(PostgisDatabase.jdbc());
    }

    private static void run(Instant startedAt, String status, String error) {
        PostgisDatabase.jdbc().sql("INSERT INTO master.sync_run (id, started_at, finished_at, status, processed, failed, error_message) "
                        + "VALUES (:id, :started, :finished, :status, 10, 1, :error)")
                .param("id", UUID.randomUUID()).param("started", java.sql.Timestamp.from(startedAt))
                .param("finished", "RUNNING".equals(status) ? null : java.sql.Timestamp.from(startedAt.plusSeconds(60)))
                .param("status", status).param("error", error).update();
    }

    @Test
    @DisplayName("lists sync runs newest first, including one still running")
    void listsRunsNewestFirst() {
        Instant now = Instant.parse("2026-09-29T03:00:00Z");
        run(now.minusSeconds(86_400), "SUCCEEDED", null);
        run(now, "PARTIAL", "OCM: timeout");
        run(now.plusSeconds(3_600), "RUNNING", null);

        var runs = repository.syncRuns(2);

        assertThat(runs).extracting(AdminController.SyncRun::status).containsExactly("RUNNING", "PARTIAL");
        assertThat(runs.get(0).finishedAt()).isNull();
        assertThat(runs.get(1).errorMessage()).isEqualTo("OCM: timeout");
    }

    @Test
    @DisplayName("counts stations, charge points, accounts and comments")
    void countsOverview() {
        UUID station = PostgisDatabase.insertStation("EnBW", "EnBW", "DE", 48.77, 9.18);
        PostgisDatabase.insertChargePoint(station, "1", "DE*EBW*E1*1");

        assertThat(repository.overview()).isEqualTo(new AdminController.Overview(1, 1, 0, 0));
    }
}
