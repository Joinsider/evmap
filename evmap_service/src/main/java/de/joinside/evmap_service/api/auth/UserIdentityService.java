package de.joinside.evmap_service.api.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
class UserIdentityService {
    private final UserIdentityRepository identities;

    UserIdentityService(UserIdentityRepository identities) {
        this.identities = identities;
    }

    @Transactional
    UUID findOrCreate(String provider, String subject) {
        return identities.findByProviderAndProviderSubject(provider, subject).map(identity -> {
            identity.recordLogin();
            return identity.id;
        }).orElseGet(() -> identities.save(new UserIdentity(provider, subject)).id);
    }
}
