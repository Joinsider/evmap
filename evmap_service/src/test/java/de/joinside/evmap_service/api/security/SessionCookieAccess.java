package de.joinside.evmap_service.api.security;

import java.time.Duration;

/** Lets tests outside this package build the package-private {@link SessionCookie}. */
public final class SessionCookieAccess {
    public SessionCookie cookie() {
        return new SessionCookie(Duration.ofHours(1), true);
    }
}
