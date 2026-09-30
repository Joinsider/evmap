package de.joinside.evmap_service.api.stationreport;

import de.joinside.evmap_service.api.station.StationController.StationNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Filing and closing station reports (ADR 0021). The free text is the person's own data and stays out of
 * the log (ADR 0002); ids, reasons and counts are enough to follow a case.
 */
@Service
class StationReportService {
    private static final Logger log = LoggerFactory.getLogger(StationReportService.class);

    static final int MAX_NOTE_LENGTH = 500;

    private final StationReportRepository repository;

    StationReportService(StationReportRepository repository) {
        this.repository = repository;
    }

    void report(UUID stationId, UUID reporter, String reason, String note) {
        StationReportReason parsed = StationReportReason.parse(reason);
        String cleaned = note == null || note.isBlank() ? null : note.strip();
        if (cleaned != null && cleaned.length() > MAX_NOTE_LENGTH) throw new IllegalArgumentException("note is too long");
        if (!repository.stationExists(stationId)) throw new StationNotFoundException(stationId);
        boolean added = repository.report(stationId, reporter, parsed.token(), cleaned);
        log.info("Station {} reported as {} by account {} (new={}, note={})", stationId, parsed.token(), reporter, added, cleaned != null);
    }

    List<StationReportController.ReportedStation> openGroups() {
        return repository.openGroups();
    }

    void close(UUID stationId, String reason, UUID admin, String status) {
        StationReportReason parsed = StationReportReason.parse(reason);
        int closed = repository.close(stationId, parsed.token(), admin, status);
        if (closed == 0) throw new StationNotFoundException(stationId);
        log.info("Admin {} set {} report(s) on station {} ({}) to {}", admin, closed, stationId, parsed.token(), status);
    }
}
