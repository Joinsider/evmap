package de.joinside.evmap_service.api.security;

import java.util.UUID;

/**
 * Whether an account still exists. Access tokens are stateless and live for hours, so without this
 * check a deleted account's token would keep working until it ran out (ADR 0020). One primary-key
 * lookup per authenticated request.
 */
public interface KnownAccounts {
    boolean exists(UUID accountId);
}
