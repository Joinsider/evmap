package de.joinside.evmap_service.api.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The admin area's read side (ADR 0018). Only reachable with the account's admin flag — enforced in
 * {@code SecurityConfiguration}, not here. Phase 1 shows ingestion health and a few counts; the
 * moderation queues of later phases join this controller's path.
 */
@RestController
@RequestMapping("/api/v1/admin")
class AdminController {
    private static final int MAX_RUNS = 100;

    private final AdminRepository repository;

    AdminController(AdminRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/overview")
    Overview overview() {
        return repository.overview();
    }

    @GetMapping("/sync-runs")
    List<SyncRun> syncRuns(@RequestParam(defaultValue = "20") int limit) {
        return repository.syncRuns(Math.clamp(limit, 1, MAX_RUNS));
    }

    record Overview(long stations, long chargePoints, long accounts, long comments) {
    }

    record SyncRun(UUID id, Instant startedAt, Instant finishedAt, String status, int processed, int created,
                   int updated, int unchanged, int failed, String errorMessage) {
    }
}
