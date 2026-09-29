package de.joinside.evmap_service.api;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTests {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void badRequestCarriesTheMessage() {
        assertThat(handler.badRequest(new IllegalArgumentException("nope"))).containsEntry("error", "nope");
    }

    @Test
    void rejectedIdentityTokenDoesNotLeakTheReason() {
        assertThat(handler.invalidIdentityToken(new BadJwtException("expired at ...")))
                .containsEntry("error", "Invalid identity token");
    }

    @Test
    void notFoundIsGeneric() {
        assertThat(handler.notFound(new RuntimeException("Station not found: 1")))
                .containsEntry("error", "Not found");
    }
}
