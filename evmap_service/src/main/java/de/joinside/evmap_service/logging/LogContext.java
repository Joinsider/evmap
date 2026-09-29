package de.joinside.evmap_service.logging;

import org.slf4j.MDC;

/**
 * Central definition of the diagnostic context keys used across the whole service.
 * <p>
 * Every log line — plain text locally, ECS JSON in Docker — carries whatever is present in the MDC,
 * so the keys defined here are the contract between the code and whatever collects the logs.
 * Never put secrets (tokens, identity tokens) into the context.
 */
public final class LogContext {
    /** Correlation id of the current HTTP request, echoed back via the {@code X-Request-Id} header. */
    public static final String REQUEST_ID = "requestId";
    /** Internal account id (user_data.account) of the authenticated caller, if any. */
    public static final String USER_ID = "userId";
    public static final String HTTP_METHOD = "httpMethod";
    public static final String HTTP_PATH = "httpPath";
    public static final String HTTP_STATUS = "httpStatus";
    public static final String DURATION_MS = "durationMs";
    /** Name of the background job producing the log line (sync deployable). */
    public static final String JOB = "job";
    /** External data source a sync log line belongs to (BNetzA, OCM, ...). */
    public static final String SOURCE = "source";

    private LogContext() {
    }

    public static void put(String key, Object value) {
        if (value != null) MDC.put(key, value.toString());
    }

    /**
     * Opens a scope that removes the key again when closed — for background jobs without a request
     * context.
     * <p>
     * <strong>Do not nest two scopes on the same key.</strong> Closing one <em>removes</em> the key
     * rather than restoring what an enclosing scope had put there, so the inner scope silently
     * unsets the outer one for everything that follows. Each key needs a single owner: the sync run
     * owns {@link #SOURCE} for as long as it is ingesting that source, and {@code RequestLoggingFilter}
     * owns {@link #REQUEST_ID} for the lifetime of a request. Nesting <em>different</em> keys — a
     * {@link #SOURCE} inside a {@link #JOB} — is fine and intended.
     */
    public static MDC.MDCCloseable scope(String key, Object value) {
        return MDC.putCloseable(key, String.valueOf(value));
    }

    public static void remove(String... keys) {
        for (String key : keys) MDC.remove(key);
    }
}
