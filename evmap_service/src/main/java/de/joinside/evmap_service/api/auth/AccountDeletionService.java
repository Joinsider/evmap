package de.joinside.evmap_service.api.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Deletes an account for good (ADR 0020): first the provider-side cleanup Apple demands, then the
 * rows. The Apple call is a network round trip and deliberately runs outside any database transaction;
 * if it fails the deletion still happens, because the person's right to erasure must not depend on
 * Apple being reachable, and the only cost is a token that stays valid at Apple.
 */
@Service
class AccountDeletionService {
    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    private final AccountRepository accounts;
    private final AppleTokens appleTokens;

    AccountDeletionService(AccountRepository accounts, AppleTokens appleTokens) {
        this.accounts = accounts;
        this.appleTokens = appleTokens;
    }

    void delete(UUID accountId) {
        List<AccountRepository.StoredRefreshToken> tokens = accounts.refreshTokens(accountId);
        int revoked = 0;
        for (AccountRepository.StoredRefreshToken token : tokens)
            if (appleTokens.revoke(token.encryptedValue(), token.clientId())) revoked++;
        long untracked = accounts.appleIdentitiesWithoutToken(accountId);
        if (untracked > 0)
            log.warn("Account {} has {} Apple sign-in(s) without a stored refresh token; nothing to revoke for them", accountId, untracked);
        boolean deleted = accounts.delete(accountId);
        // Ids and counts only (ADR 0002): what was revoked is enough to answer "did Apple hear about this?".
        log.info("Deleted account {} (found={}, apple tokens stored={}, revoked={})", accountId, deleted, tokens.size(), revoked);
    }
}
