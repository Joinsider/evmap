package de.joinside.evmap_service.api;

/** Lets tests in sub-packages attach the package-private exception handler to a standalone MockMvc. */
public final class ApiExceptionHandlerAccess {
    public Object handler() {
        return new ApiExceptionHandler();
    }
}
