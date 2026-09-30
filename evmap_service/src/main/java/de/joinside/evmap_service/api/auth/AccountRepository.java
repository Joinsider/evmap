package de.joinside.evmap_service.api.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code user_data.account} and {@code user_data.provider_identity}. Plain SQL, because linking is a
 * lookup across identities by address that reads more clearly as a query than as entity navigation,
 * and so the tests can run it against the real schema.
 */
@Repository
class AccountRepository {
    private static final String PROVIDER = "provider";
    private static final String SUBJECT = "subject";
    private static final String EMAIL = "email";

    private final JdbcClient jdbc;

    AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<UUID> accountOf(Provider provider, String subject) {
        return jdbc.sql("SELECT account_id FROM user_data.provider_identity WHERE provider = :provider AND provider_subject = :subject")
                .param(PROVIDER, provider.token()).param(SUBJECT, subject)
                .query(UUID.class).optional();
    }

    /**
     * Accounts that already hold {@code email} as a verified address, oldest first. Normally at most
     * one, because every later verified sign-in with the same address is linked to it.
     */
    List<UUID> accountsWithVerifiedEmail(String email) {
        return jdbc.sql("SELECT DISTINCT a.id, a.created_at FROM user_data.account a "
                        + "JOIN user_data.provider_identity i ON i.account_id = a.id "
                        + "WHERE i.email_verified AND lower(i.email) = lower(:email) ORDER BY a.created_at, a.id")
                .param(EMAIL, email)
                .query((rs, row) -> rs.getObject("id", UUID.class)).list();
    }

    void createAccount(UUID id) {
        jdbc.sql("INSERT INTO user_data.account (id) VALUES (:id)").param("id", id).update();
    }

    void createIdentity(UUID id, UUID accountId, VerifiedIdentity identity) {
        jdbc.sql("INSERT INTO user_data.provider_identity (id, account_id, provider, provider_subject, email, email_verified) "
                        + "VALUES (:id, :account, :provider, :subject, :email, :verified)")
                .param("id", id).param("account", accountId).param(PROVIDER, identity.provider().token())
                .param(SUBJECT, identity.subject()).param(EMAIL, identity.email()).param("verified", identity.emailVerified())
                .update();
    }

    /** Refreshes what the provider says now: an address can change or become verified. */
    void recordLogin(UUID accountId, VerifiedIdentity identity) {
        jdbc.sql("UPDATE user_data.provider_identity SET email = :email, email_verified = :verified, last_login_at = now() "
                        + "WHERE provider = :provider AND provider_subject = :subject")
                .param(EMAIL, identity.email()).param("verified", identity.emailVerified())
                .param(PROVIDER, identity.provider().token()).param(SUBJECT, identity.subject())
                .update();
        jdbc.sql("UPDATE user_data.account SET last_login_at = now() WHERE id = :id").param("id", accountId).update();
    }

    boolean exists(UUID accountId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM user_data.account WHERE id = :id)").param("id", accountId)
                .query(Boolean.class).single();
    }

    /** Keeps the encrypted refresh token of the identity that just signed in (ADR 0020). */
    void storeRefreshToken(Provider provider, String subject, String encryptedToken, String clientId) {
        jdbc.sql("UPDATE user_data.provider_identity SET refresh_token = :token, refresh_token_client_id = :client "
                        + "WHERE provider = :provider AND provider_subject = :subject")
                .param("token", encryptedToken).param("client", clientId).param(PROVIDER, provider.token()).param(SUBJECT, subject)
                .update();
    }

    List<StoredRefreshToken> refreshTokens(UUID accountId) {
        return jdbc.sql("SELECT refresh_token, refresh_token_client_id FROM user_data.provider_identity "
                        + "WHERE account_id = :id AND refresh_token IS NOT NULL")
                .param("id", accountId)
                .query((rs, row) -> new StoredRefreshToken(rs.getString("refresh_token"), rs.getString("refresh_token_client_id"))).list();
    }

    /** Apple sign-ins that never left a refresh token, so their deletion cannot revoke anything. */
    long appleIdentitiesWithoutToken(UUID accountId) {
        return jdbc.sql("SELECT count(*) FROM user_data.provider_identity WHERE account_id = :id AND provider = 'apple' AND refresh_token IS NULL")
                .param("id", accountId).query(Long.class).single();
    }

    /**
     * Removes the account; identities, comments, reports and blocks follow through {@code ON DELETE
     * CASCADE}. Everything user-owned references the account, which is what makes this one statement.
     */
    boolean delete(UUID accountId) {
        return jdbc.sql("DELETE FROM user_data.account WHERE id = :id").param("id", accountId).update() > 0;
    }

    boolean isAdmin(UUID accountId) {
        return jdbc.sql("SELECT is_admin FROM user_data.account WHERE id = :id").param("id", accountId)
                .query(Boolean.class).optional().orElse(false);
    }

    List<LinkedIdentity> identities(UUID accountId) {
        return jdbc.sql("SELECT provider, email FROM user_data.provider_identity WHERE account_id = :id ORDER BY created_at")
                .param("id", accountId)
                .query((rs, row) -> new LinkedIdentity(rs.getString(PROVIDER), rs.getString(EMAIL))).list();
    }

    record LinkedIdentity(String provider, String email) {
    }

    /** The value is encrypted; {@code clientId} is what revoking it at the provider has to name. */
    record StoredRefreshToken(String encryptedValue, String clientId) {
        @Override
        public String toString() {
            return "StoredRefreshToken[clientId=" + clientId + "]";
        }
    }
}
