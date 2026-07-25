package de.joinside.evmap_service.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccessTokenServiceTests {
    private final AccessTokenService tokens = new AccessTokenService("test-secret-with-sufficient-length", Duration.ofHours(1));

    @Test void roundTripsTheInternalIdentityOnly() {
        UUID identityId = UUID.randomUUID();
        assertThat(tokens.verify(tokens.issue(identityId)).identityId()).isEqualTo(identityId);
    }
    @Test void rejectsTamperedToken() {
        String token = tokens.issue(UUID.randomUUID());
        assertThatThrownBy(() -> tokens.verify(token.substring(0, token.length() - 1) + "x")).isInstanceOf(IllegalArgumentException.class);
    }
}
