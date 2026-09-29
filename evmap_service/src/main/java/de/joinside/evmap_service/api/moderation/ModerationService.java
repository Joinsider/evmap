package de.joinside.evmap_service.api.moderation;

import de.joinside.evmap_service.api.comment.CommentController.CommentNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * What a user can do about somebody else's comment, and what an admin then decides (ADR 0020).
 * Logs carry ids only, never the comment text or a reporter's identity next to the author's.
 */
@Service
class ModerationService {
    private static final Logger log = LoggerFactory.getLogger(ModerationService.class);

    private final ModerationRepository repository;

    ModerationService(ModerationRepository repository) {
        this.repository = repository;
    }

    void report(UUID commentId, UUID reporter, String reason) {
        ReportReason parsed = ReportReason.parse(reason);
        UUID author = repository.authorOf(commentId).orElseThrow(CommentNotFoundException::new);
        if (author.equals(reporter)) throw new IllegalArgumentException("Cannot report your own comment");
        repository.report(commentId, reporter, parsed.token());
        log.info("Comment {} reported as {} by account {}", commentId, parsed.token(), reporter);
    }

    /** The author is resolved here and never returned: comments show no author, and blocks stay anonymous. */
    void blockAuthorOf(UUID commentId, UUID blocker) {
        UUID author = repository.authorOf(commentId).orElseThrow(CommentNotFoundException::new);
        if (author.equals(blocker)) throw new IllegalArgumentException("Cannot block yourself");
        repository.block(blocker, author);
        log.info("Account {} blocked the author of comment {}", blocker, commentId);
    }

    List<ModerationController.Block> blocks(UUID blocker) {
        return repository.blocks(blocker);
    }

    void unblock(UUID blockId, UUID blocker) {
        if (!repository.unblock(blockId, blocker)) throw new CommentNotFoundException();
        log.info("Account {} lifted block {}", blocker, blockId);
    }

    List<AdminReportsController.ReportedComment> openReports() {
        return repository.openReports();
    }

    void dismiss(UUID commentId, UUID admin) {
        int closed = repository.dismiss(commentId, admin);
        if (closed == 0) throw new CommentNotFoundException();
        log.info("Admin {} dismissed {} report(s) on comment {}", admin, closed, commentId);
    }

    void removeComment(UUID commentId, UUID admin) {
        if (!repository.removeComment(commentId)) throw new CommentNotFoundException();
        log.info("Admin {} removed comment {}", admin, commentId);
    }
}
