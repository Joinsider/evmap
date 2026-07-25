package de.joinside.evmap_service.api.comment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface CommentRepository extends JpaRepository<StationComment, UUID> {
    List<StationComment> findByStationIdOrderByCreatedAtDesc(UUID stationId);

    Optional<StationComment> findByIdAndUserIdentityId(UUID id, UUID userIdentityId);
}
