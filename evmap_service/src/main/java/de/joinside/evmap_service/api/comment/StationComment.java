package de.joinside.evmap_service.api.comment;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "station_comment", schema = "user_data")
class StationComment {
    @Id
    UUID id;
    @Column(name = "station_id", nullable = false)
    UUID stationId;
    @Column(name = "account_id", nullable = false)
    UUID accountId;
    @Column(nullable = false)
    String body;
    @Column(name = "paid_price_cents")
    Integer paidPriceCents;
    String experience;
    @Column(name = "created_at", nullable = false)
    Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    protected StationComment() {
    }

    StationComment(UUID stationId, UUID accountId, CommentController.CommentRequest request) {
        id = UUID.randomUUID();
        this.stationId = stationId;
        this.accountId = accountId;
        createdAt = Instant.now();
        apply(request);
        updatedAt = createdAt;
    }

    void apply(CommentController.CommentRequest request) {
        body = request.body().trim();
        paidPriceCents = request.paidPriceCents();
        experience = request.experience();
    }

    @PreUpdate
    void markUpdated() {
        updatedAt = Instant.now();
    }
}
