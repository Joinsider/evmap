package de.joinside.evmap_service.api.auth;

import de.joinside.evmap_service.api.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

    AccountController(AccountService accounts) {
        this.accounts = accounts;
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
