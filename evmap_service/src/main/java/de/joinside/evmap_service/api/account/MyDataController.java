package de.joinside.evmap_service.api.account;

import de.joinside.evmap_service.api.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * The caller's own data (ADR 0020): a portable export for GDPR Art. 15/20, and the same material
 * without the account internals as "my contributions". A direct download — one account's data is small.
 */
@RestController
class MyDataController {
    private static final Logger log = LoggerFactory.getLogger(MyDataController.class);

    private final MyDataRepository data;
    private final Clock clock;

    MyDataController(MyDataRepository data) {
        this.data = data;
        this.clock = Clock.systemUTC();
    }

    @GetMapping("/api/v1/me/export")
    ResponseEntity<DataExport> export(@AuthenticationPrincipal CurrentUser user) {
        UUID id = user.accountId();
        AccountData account = data.account(id).orElseThrow(() -> new IllegalArgumentException("Unknown account"));
        DataExport export = new DataExport(clock.instant(), account, data.identities(id), data.comments(id), data.reports(id), data.blocks(id));
        // Counts only: the content is the person's, not the log's (ADR 0002).
        log.info("Exported the data of account {} ({} comments, {} reports, {} blocks)", id, export.comments().size(),
                export.reportsFiled().size(), export.blocks().size());
        String filename = "evmap-export-" + LocalDate.now(clock.withZone(ZoneOffset.UTC)) + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(export);
    }

    @GetMapping("/api/v1/me/contributions")
    Contributions contributions(@AuthenticationPrincipal CurrentUser user) {
        return new Contributions(data.comments(user.accountId()), data.reports(user.accountId()));
    }

    record DataExport(Instant exportedAt, AccountData account, List<IdentityData> identities, List<CommentData> comments,
                      List<ReportData> reportsFiled, List<BlockData> blocks) {
    }

    record Contributions(List<CommentData> comments, List<ReportData> reports) {
    }

    record AccountData(UUID id, boolean admin, Instant createdAt, Instant lastLoginAt) {
    }

    record IdentityData(String provider, String subject, String email, boolean emailVerified, Instant createdAt, Instant lastLoginAt) {
    }

    record CommentData(UUID id, UUID stationId, String stationName, String body, Integer paidPriceCents, String experience,
                       Instant createdAt, Instant updatedAt) {
    }

    record ReportData(UUID id, String reason, String status, String stationName, Instant createdAt, Instant resolvedAt) {
    }

    record BlockData(UUID id, Instant createdAt) {
    }
}
