package de.joinside.evmap_service.api.comment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
class CommentService {
    private static final Logger log = LoggerFactory.getLogger(CommentService.class);

    private final CommentRepository comments;

    CommentService(CommentRepository comments) {
        this.comments = comments;
    }

    List<CommentController.CommentResponse> list(UUID stationId, UUID userId) {
        return comments.findByStationIdOrderByCreatedAtDesc(stationId).stream().map(comment -> response(comment, userId)).toList();
    }

    @Transactional
    CommentController.CommentResponse create(UUID stationId, UUID userId, CommentController.CommentRequest request) {
        validate(request);
        StationComment saved = comments.save(new StationComment(stationId, userId, request));
        // User-generated text stays out of the log; ids are enough to follow a moderation case.
        log.info("Comment {} created for station {} by account {}", saved.id, stationId, userId);
        return response(saved, userId);
    }

    @Transactional
    CommentController.CommentResponse update(UUID id, UUID userId, CommentController.CommentRequest request) {
        validate(request);
        StationComment comment = findOwned(id, userId);
        comment.apply(request);
        log.info("Comment {} updated by account {}", id, userId);
        return response(comment, userId);
    }

    @Transactional
    void delete(UUID id, UUID userId) {
        comments.delete(findOwned(id, userId));
        log.info("Comment {} deleted by account {}", id, userId);
    }

    private StationComment findOwned(UUID id, UUID userId) {
        return comments.findByIdAndAccountId(id, userId).orElseThrow(() -> {
            // Also covers "exists but belongs to somebody else" — worth seeing when it happens repeatedly.
            log.warn("Account {} tried to modify comment {} it does not own (or that does not exist)", userId, id);
            return new CommentController.CommentNotFoundException();
        });
    }

    private CommentController.CommentResponse response(StationComment comment, UUID userId) {
        return new CommentController.CommentResponse(comment.id, comment.body, comment.paidPriceCents, comment.experience,
                comment.createdAt, comment.updatedAt, userId != null && userId.equals(comment.accountId));
    }

    private void validate(CommentController.CommentRequest request) {
        if (request.body() == null || request.body().isBlank() || request.body().length() > 2000 || request.paidPriceCents() != null && request.paidPriceCents() < 0) {
            log.warn("Rejected invalid comment (bodyLength={}, paidPriceCents={})",
                    request.body() == null ? null : request.body().length(), request.paidPriceCents());
            throw new IllegalArgumentException("Invalid comment");
        }
    }
}
