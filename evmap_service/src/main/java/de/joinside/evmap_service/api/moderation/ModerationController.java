package de.joinside.evmap_service.api.moderation;

import de.joinside.evmap_service.api.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * Reporting a comment and blocking its author (ADR 0020). Both act through a comment id, because
 * comments show no author; a block is listed as an anonymous entry the user can lift later.
 */
@RestController
class ModerationController {
    private final ModerationService moderation;

    ModerationController(ModerationService moderation) {
        this.moderation = moderation;
    }

    @PostMapping("/api/v1/comments/{id}/report")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void report(@PathVariable UUID id, @RequestBody ReportRequest request, @AuthenticationPrincipal CurrentUser user) {
        moderation.report(id, user.accountId(), request.reason());
    }

    @PostMapping("/api/v1/comments/{id}/block-author")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void blockAuthor(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser user) {
        moderation.blockAuthorOf(id, user.accountId());
    }

    @GetMapping("/api/v1/me/blocks")
    List<Block> blocks(@AuthenticationPrincipal CurrentUser user) {
        return moderation.blocks(user.accountId());
    }

    @DeleteMapping("/api/v1/me/blocks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unblock(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser user) {
        moderation.unblock(id, user.accountId());
    }

    record ReportRequest(String reason) {
    }

    /** {@code id} identifies the block, not the person: it is only good for lifting it again. */
    record Block(UUID id, Instant createdAt) {
    }
}
