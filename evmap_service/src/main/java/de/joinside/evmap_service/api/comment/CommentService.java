package de.joinside.evmap_service.api.comment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
class CommentService {
    private final CommentRepository comments;

    CommentService(CommentRepository comments) {
        this.comments = comments;
    }

    List<CommentController.CommentResponse> list(UUID stationId) {
        return comments.findByStationIdOrderByCreatedAtDesc(stationId).stream().map(this::response).toList();
    }

    @Transactional
    CommentController.CommentResponse create(UUID stationId, UUID userId, CommentController.CommentRequest request) {
        validate(request);
        return response(comments.save(new StationComment(stationId, userId, request)));
    }

    @Transactional
    CommentController.CommentResponse update(UUID id, UUID userId, CommentController.CommentRequest request) {
        validate(request);
        StationComment comment = findOwned(id, userId);
        comment.apply(request);
        return response(comment);
    }

    @Transactional
    void delete(UUID id, UUID userId) {
        comments.delete(findOwned(id, userId));
    }

    private StationComment findOwned(UUID id, UUID userId) {
        return comments.findByIdAndUserIdentityId(id, userId).orElseThrow(CommentController.CommentNotFoundException::new);
    }

    private CommentController.CommentResponse response(StationComment comment) {
        return new CommentController.CommentResponse(comment.id, comment.body, comment.paidPriceCents, comment.experience, comment.createdAt, comment.updatedAt);
    }

    private void validate(CommentController.CommentRequest request) {
        if (request.body() == null || request.body().isBlank() || request.body().length() > 2000 || request.paidPriceCents() != null && request.paidPriceCents() < 0)
            throw new IllegalArgumentException("Invalid comment");
    }
}
