package de.joinside.evmap_service.api.comment;

import de.joinside.evmap_service.api.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class CommentController {
    private final CommentService comments;

    CommentController(CommentService comments) {
        this.comments = comments;
    }

    @GetMapping("/api/v1/stations/{stationId}/comments")
    List<CommentResponse> list(@PathVariable UUID stationId) {
        return comments.list(stationId);
    }

    @PostMapping("/api/v1/stations/{stationId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    CommentResponse create(@PathVariable UUID stationId, @RequestBody CommentRequest request, @AuthenticationPrincipal CurrentUser user) {
        return comments.create(stationId, user.identityId(), request);
    }

    @PatchMapping("/api/v1/comments/{id}")
    CommentResponse update(@PathVariable UUID id, @RequestBody CommentRequest request, @AuthenticationPrincipal CurrentUser user) {
        return comments.update(id, user.identityId(), request);
    }

    @DeleteMapping("/api/v1/comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser user) {
        comments.delete(id, user.identityId());
    }

    record CommentRequest(String body, Integer paidPriceCents, String experience) {
    }

    record CommentResponse(UUID id, String body, Integer paidPriceCents, String experience, Instant createdAt,
                           Instant updatedAt) {
    }

    public static class CommentNotFoundException extends RuntimeException {
    }
}
