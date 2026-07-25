package de.joinside.evmap_service.api.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_identity", schema = "user_data")
class UserIdentity {
    @Id
    UUID id;
    @Column(nullable = false)
    String provider;
    @Column(name = "provider_subject", nullable = false)
    String providerSubject;
    @Column(name = "created_at", nullable = false)
    Instant createdAt;
    @Column(name = "last_login_at", nullable = false)
    Instant lastLoginAt;

    protected UserIdentity() {
    }

    UserIdentity(String provider, String providerSubject) {
        id = UUID.randomUUID();
        this.provider = provider;
        this.providerSubject = providerSubject;
        createdAt = Instant.now();
        lastLoginAt = createdAt;
    }

    void recordLogin() {
        lastLoginAt = Instant.now();
    }
}
