package de.joinside.evmap_service.api;

import de.joinside.evmap_service.api.comment.CommentController.CommentNotFoundException;
import de.joinside.evmap_service.api.station.StationController.StationNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler({IllegalArgumentException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> badRequest(RuntimeException ex) {
        return Map.of("error", ex.getMessage());
    }

    @ExceptionHandler({StationNotFoundException.class, CommentNotFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    Map<String, String> notFound(RuntimeException ex) {
        return Map.of("error", "Not found");
    }
}
