package de.joinside.evmap_service.api.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
class UserIdentityService {
    private static final Logger log = LoggerFactory.getLogger(UserIdentityService.class);

    private final UserIdentityRepository identities;

    UserIdentityService(UserIdentityRepository identities) {
        this.identities = identities;
    }

    @Transactional
    UUID findOrCreate(String provider, String subject) {
        // The provider subject is a pseudonymous user identifier — log the internal id instead.
        return identities.findByProviderAndProviderSubject(provider, subject).map(identity -> {
            identity.recordLogin();
            log.debug("Recorded login for identity {} (provider {})", identity.id, provider);
            return identity.id;
        }).orElseGet(() -> {
            UUID created = identities.save(new UserIdentity(provider, subject)).id;
            log.info("Created new user identity {} (provider {})", created, provider);
            return created;
        });
    }
}
