package de.joinside.evmap_service.api.security;

import java.util.UUID;

/**
 * Whether an account carries the admin flag. Asked per request on {@code /api/v1/admin/**} instead of
 * being written into the access token, so revoking the flag in the database takes effect at once
 * rather than when a 12-hour token runs out.
 */
public interface AdminAccounts {
    boolean isAdmin(UUID accountId);
}
