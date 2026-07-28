package de.joinside.evmap_service.api;

import de.joinside.evmap_service.api.comment.CommentController.CommentNotFoundException;
import de.joinside.evmap_service.api.station.StationController.StationNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({IllegalArgumentException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> badRequest(RuntimeException ex) {
        // The offending call site already logged the details; this is the "answered with 400" marker.
        log.debug("Responding 400: {}", ex.getMessage());
        return Map.of("error", ex.getMessage());
    }

    /**
     * A rejected Apple identity token is a failed credential, not a server fault. Only
     * {@link BadJwtException} — malformed, expired, wrong issuer/audience — maps here; a plain
     * {@code JwtException} means Apple's JWKS endpoint failed us and must stay a 5xx.
     */
    @ExceptionHandler(BadJwtException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    Map<String, String> invalidIdentityToken(BadJwtException ex) {
        log.debug("Responding 401: {}", ex.getMessage());
        return Map.of("error", "Invalid identity token");
    }

    @ExceptionHandler({StationNotFoundException.class, CommentNotFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    Map<String, String> notFound(RuntimeException ex) {
        log.debug("Responding 404: {}", ex.getMessage());
        return Map.of("error", "Not found");
    }
}
