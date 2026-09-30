package de.joinside.evmap_service.api.auth;

import de.joinside.evmap_service.api.security.AdminAccounts;
import de.joinside.evmap_service.api.security.KnownAccounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns a provider identity into the internal account the rest of the system refers to (ADR 0018).
 * <p>
 * A known identity signs in to its account. A new one is attached to an existing account only when
 * both sides carry the <em>same verified</em> address; everything else gets a new account. Linking
 * happens only at an identity's first sign-in — an address verified later never merges two accounts
 * that already exist, because that would silently move one person's contributions to another.
 */
@Service
class AccountService implements AdminAccounts, KnownAccounts {
    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accounts;
    private final TokenCipher cipher;

    AccountService(AccountRepository accounts, TokenCipher cipher) {
        this.accounts = accounts;
        this.cipher = cipher;
    }

    @Transactional
    UUID signIn(VerifiedIdentity identity) {
        UUID account = resolve(identity);
        ProviderRefreshToken refreshToken = identity.refreshToken();
        if (refreshToken != null && cipher.enabled())
            accounts.storeRefreshToken(identity.provider(), identity.subject(), cipher.encrypt(refreshToken.value()), refreshToken.clientId());
        return account;
    }

    // Logs name the account uuid and the provider only — never the subject or the address (ADR 0002).
    private UUID resolve(VerifiedIdentity identity) {
        Optional<UUID> known = accounts.accountOf(identity.provider(), identity.subject());
        if (known.isPresent()) {
            accounts.recordLogin(known.get(), identity);
            log.debug("Recorded login for account {} (provider {})", known.get(), identity.provider().token());
            return known.get();
        }

        if (identity.linkable()) {
            List<UUID> matches = accounts.accountsWithVerifiedEmail(identity.email());
            if (!matches.isEmpty()) {
                UUID account = matches.getFirst();
                if (matches.size() > 1)
                    log.warn("{} accounts share a verified address; linking to the oldest, {}", matches.size(), account);
                accounts.createIdentity(UUID.randomUUID(), account, identity);
                accounts.recordLogin(account, identity);
                log.info("Linked a new {} identity to account {} by verified e-mail", identity.provider().token(), account);
                return account;
            }
        }

        // The first identity reuses the account's uuid, like every identity the migration carried over.
        UUID created = UUID.randomUUID();
        accounts.createAccount(created);
        accounts.createIdentity(created, created, identity);
        log.info("Created account {} (provider {})", created, identity.provider().token());
        return created;
    }

    @Override
    public boolean exists(UUID accountId) {
        return accounts.exists(accountId);
    }

    @Override
    public boolean isAdmin(UUID accountId) {
        return accounts.isAdmin(accountId);
    }

    List<AccountRepository.LinkedIdentity> identities(UUID accountId) {
        return accounts.identities(accountId);
    }
}
