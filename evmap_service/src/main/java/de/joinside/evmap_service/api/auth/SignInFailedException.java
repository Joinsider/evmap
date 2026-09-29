package de.joinside.evmap_service.api.auth;

/**
 * A provider refused to confirm who the caller is. Answered with 401 — a failed credential, not a
 * server fault. The message may name the provider's error code but never a token, code or address.
 */
public class SignInFailedException extends RuntimeException {
    public SignInFailedException(String message) {
        super(message);
    }

    public SignInFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
