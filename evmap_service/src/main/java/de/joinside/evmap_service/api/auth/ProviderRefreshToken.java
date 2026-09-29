package de.joinside.evmap_service.api.auth;

/**
 * A refresh token a provider issued at sign-in, with the client id it was issued to. Kept only so the
 * account can be revoked at the provider when it is deleted (ADR 0020). {@link #toString()} hides the
 * value, so a stray log line or assertion message cannot leak it.
 */
record ProviderRefreshToken(String value, String clientId) {
    @Override
    public String toString() {
        return "ProviderRefreshToken[clientId=" + clientId + ", value=<redacted>]";
    }
}
