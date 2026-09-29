package de.joinside.evmap_service.api.auth;

import de.joinside.evmap_service.api.security.CurrentUser;
import de.joinside.evmap_service.api.security.SessionCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The signed-in caller's own account. The web client reads {@code admin} to decide whether to show
 * the admin area; that is presentation only — {@code /api/v1/admin/**} checks the flag itself.
 */
@RestController
class AccountController {
    private final AccountService accounts;
    private final AccountDeletionService deletion;
    private final SessionCookie sessionCookie;

    AccountController(AccountService accounts, AccountDeletionService deletion, SessionCookie sessionCookie) {
        this.accounts = accounts;
        this.deletion = deletion;
        this.sessionCookie = sessionCookie;
    }

    /**
     * Deletes the caller's account and everything attached to it (ADR 0020). The token stops working
     * at once because the account no longer exists; the cookie is cleared for the web client.
     */
    @DeleteMapping("/api/v1/me")
    ResponseEntity<Void> delete(@AuthenticationPrincipal CurrentUser user) {
        deletion.delete(user.accountId());
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, sessionCookie.clear().toString()).build();
    }

    @GetMapping("/api/v1/me")
    AccountResponse me(@AuthenticationPrincipal CurrentUser user) {
        List<LinkedIdentity> identities = accounts.identities(user.accountId()).stream()
                .map(identity -> new LinkedIdentity(identity.provider(), identity.email())).toList();
        return new AccountResponse(user.accountId(), accounts.isAdmin(user.accountId()), identities);
    }

    record AccountResponse(UUID id, boolean admin, List<LinkedIdentity> identities) {
    }

    record LinkedIdentity(String provider, String email) {
    }
}
