package de.joinside.evmap_service.api.moderation;

import de.joinside.evmap_service.api.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The moderation queue (ADR 0020). Reachable only with the admin flag — enforced for
 * {@code /api/v1/admin/**} in {@code SecurityConfiguration}, not here. An admin sees the reported
 * comment and how it was reported, never who reported it, and can only remove the comment or dismiss
 * the reports: there is no account ban.
 */
@RestController
@RequestMapping("/api/v1/admin")
class AdminReportsController {
    private final ModerationService moderation;

    AdminReportsController(ModerationService moderation) {
        this.moderation = moderation;
    }

    @GetMapping("/reports")
    List<ReportedComment> reports() {
        return moderation.openReports();
    }

    @PostMapping("/reports/{commentId}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void dismiss(@PathVariable UUID commentId, @AuthenticationPrincipal CurrentUser admin) {
        moderation.dismiss(commentId, admin.accountId());
    }

    @DeleteMapping("/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeComment(@PathVariable UUID commentId, @AuthenticationPrincipal CurrentUser admin) {
        moderation.removeComment(commentId, admin.accountId());
    }

    /** {@code reasons} counts the open reports per reason token. */
    record ReportedComment(UUID commentId, UUID stationId, String stationName, String body, Integer paidPriceCents,
                           String experience, Instant commentCreatedAt, Instant lastReportedAt, Map<String, Integer> reasons) {
    }
}
