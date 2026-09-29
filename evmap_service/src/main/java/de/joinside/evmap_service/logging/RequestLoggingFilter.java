package de.joinside.evmap_service.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Access log and correlation id for every HTTP request.
 * <p>
 * Runs before the Spring Security filter chain so that even rejected requests are logged, and owns the
 * lifetime of the whole diagnostic context ({@link LogContext}) — downstream components such as
 * {@code BearerTokenFilter} only add to it.
 */
@Component
@Order(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 10)
public class RequestLoggingFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private static final Logger log = LoggerFactory.getLogger("de.joinside.evmap_service.access");
    private static final String COMPLETED = "<-- {} {} {} ({} ms)";
    private static final String HEALTH_PATH = "/actuator/health";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith(HEALTH_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = requestId(request);
        long startedAt = System.nanoTime();

        LogContext.put(LogContext.REQUEST_ID, requestId);
        LogContext.put(LogContext.HTTP_METHOD, request.getMethod());
        LogContext.put(LogContext.HTTP_PATH, request.getRequestURI());
        response.setHeader(REQUEST_ID_HEADER, requestId);

        log.debug("--> {} {}{}", request.getMethod(), request.getRequestURI(), query(request));
        try {
            chain.doFilter(request, response);
            logCompletion(request, response.getStatus(), startedAt, null);
        } catch (Exception exception) {
            logCompletion(request, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, startedAt, exception);
            throw exception;
        } finally {
            LogContext.remove(LogContext.REQUEST_ID, LogContext.HTTP_METHOD, LogContext.HTTP_PATH,
                    LogContext.HTTP_STATUS, LogContext.DURATION_MS, LogContext.USER_ID);
        }
    }

    private void logCompletion(HttpServletRequest request, int status, long startedAt, Exception exception) {
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        LogContext.put(LogContext.HTTP_STATUS, status);
        LogContext.put(LogContext.DURATION_MS, durationMs);

        if (exception != null) {
            log.error("<-- {} {} failed after {} ms", request.getMethod(), request.getRequestURI(), durationMs, exception);
        } else if (status >= 500) {
            log.error(COMPLETED, request.getMethod(), request.getRequestURI(), status, durationMs);
        } else if (status >= 400) {
            log.warn(COMPLETED, request.getMethod(), request.getRequestURI(), status, durationMs);
        } else {
            log.info(COMPLETED, request.getMethod(), request.getRequestURI(), status, durationMs);
        }
    }

    private String requestId(HttpServletRequest request) {
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        if (incoming == null || incoming.isBlank() || incoming.length() > 64)
            return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return incoming;
    }

    private String query(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null ? "" : "?" + query;
    }
}
