package de.joinside.evmap_service.api.stationreport;

import de.joinside.evmap_service.api.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Error reports on stations (ADR 0021). Anyone signed in can file one; the queue under
 * {@code /api/v1/admin/**} is admin-only (enforced in {@code SecurityConfiguration}, not here). An admin
 * sees what was reported and how often, never who reported it, and closing a report never edits master
 * data: the sync is its only writer.
 */
@RestController
class StationReportController {
    private final StationReportService reports;

    StationReportController(StationReportService reports) {
        this.reports = reports;
    }

    @PostMapping("/api/v1/stations/{stationId}/reports")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void report(@PathVariable UUID stationId, @RequestBody ReportRequest request, @AuthenticationPrincipal CurrentUser user) {
        reports.report(stationId, user.accountId(), request.reason(), request.note());
    }

    @GetMapping("/api/v1/admin/station-reports")
    List<ReportedStation> queue() {
        return reports.openGroups();
    }

    @PostMapping("/api/v1/admin/station-reports/{stationId}/{reason}/resolve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resolve(@PathVariable UUID stationId, @PathVariable String reason, @AuthenticationPrincipal CurrentUser admin) {
        reports.close(stationId, reason, admin.accountId(), "resolved");
    }

    @PostMapping("/api/v1/admin/station-reports/{stationId}/{reason}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void dismiss(@PathVariable UUID stationId, @PathVariable String reason, @AuthenticationPrincipal CurrentUser admin) {
        reports.close(stationId, reason, admin.accountId(), "dismissed");
    }

    record ReportRequest(String reason, String note) {
    }

    /** One line of the queue: the open reports of one station for one reason. {@code notes} are the latest free texts, newest first. */
    record ReportedStation(UUID stationId, String stationName, String operatorName, String city, String reason, int count,
                           Instant lastReportedAt, List<String> notes) {
    }
}
