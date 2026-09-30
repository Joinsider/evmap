package de.joinside.evmap_service.api.auth;

import de.joinside.evmap_service.support.PostgisDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Account deletion and the stored Apple refresh token, against the real schema (ADR 0020). */
class AccountDeletionTests {
    private final TokenCipher cipher = new TokenCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private final AppleTokens appleTokens = mock(AppleTokens.class);
    private AccountRepository repository;
    private AccountService service;
    private AccountDeletionService deletion;

    @BeforeEach
    void setUp() {
        PostgisDatabase.clearUserData();
        repository = new AccountRepository(PostgisDatabase.jdbc());
        service = new AccountService(repository, cipher);
        deletion = new AccountDeletionService(repository, appleTokens);
    }

    private static VerifiedIdentity apple(String subject, String token) {
        return new VerifiedIdentity(Provider.APPLE, subject, "ada@example.org", true, token == null ? null : new ProviderRefreshToken(token, "de.joinside.EVMap"));
    }

    private static long count(String table) {
        return PostgisDatabase.jdbc().sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    @Test
    @DisplayName("the refresh token is stored encrypted, with its client id")
    void storesEncrypted() {
        UUID account = service.signIn(apple("a-1", "r-1"));

        AccountRepository.StoredRefreshToken stored = repository.refreshTokens(account).getFirst();
        assertThat(stored.encryptedValue()).startsWith("v1:").doesNotContain("r-1");
        assertThat(stored.clientId()).isEqualTo("de.joinside.EVMap");
        assertThat(cipher.decrypt(stored.encryptedValue())).isEqualTo("r-1");
    }

    @Test
    @DisplayName("nothing is stored without an encryption key")
    void noKeyNoStorage() {
        AccountService keyless = new AccountService(repository, new TokenCipher(""));

        UUID account = keyless.signIn(apple("a-1", "r-1"));

        assertThat(repository.refreshTokens(account)).isEmpty();
    }

    @Test
    @DisplayName("a later sign-in without a token keeps the stored one; with a token it replaces it")
    void tokenLifecycle() {
        UUID account = service.signIn(apple("a-1", "r-1"));
        service.signIn(apple("a-1", null));
        assertThat(cipher.decrypt(repository.refreshTokens(account).getFirst().encryptedValue())).isEqualTo("r-1");

        service.signIn(apple("a-1", "r-2"));
        assertThat(repository.refreshTokens(account)).hasSize(1);
        assertThat(cipher.decrypt(repository.refreshTokens(account).getFirst().encryptedValue())).isEqualTo("r-2");
    }

    @Test
    @DisplayName("deleting revokes the Apple token, then removes the account with everything attached")
    void deletesEverything() {
        UUID station = PostgisDatabase.insertStation("EnBW", "EnBW", "DE", 48.77, 9.18);
        UUID account = service.signIn(apple("a-1", "r-1"));
        UUID other = service.signIn(new VerifiedIdentity(Provider.GOOGLE, "g-9", null, false));
        PostgisDatabase.jdbc().sql("INSERT INTO user_data.station_comment (id, station_id, account_id, body) VALUES (:id, :station, :account, 'ok')")
                .param("id", UUID.randomUUID()).param("station", station).param("account", account).update();
        String stored = repository.refreshTokens(account).getFirst().encryptedValue();
        when(appleTokens.revoke(stored, "de.joinside.EVMap")).thenReturn(true);

        deletion.delete(account);

        verify(appleTokens).revoke(stored, "de.joinside.EVMap");
        assertThat(repository.exists(account)).isFalse();
        assertThat(count("user_data.station_comment")).isZero();
        assertThat(repository.exists(other)).isTrue();
        assertThat(count("user_data.provider_identity")).isEqualTo(1);
    }

    @Test
    @DisplayName("the account is deleted even when Apple cannot be reached")
    void deletesWhenRevocationFails() {
        UUID account = service.signIn(apple("a-1", "r-1"));
        when(appleTokens.revoke(any(), eq("de.joinside.EVMap"))).thenReturn(false);

        deletion.delete(account);

        assertThat(repository.exists(account)).isFalse();
    }

    @Test
    @DisplayName("an account without any Apple token deletes without calling Apple")
    void noTokenNoRevocation() {
        UUID account = service.signIn(new VerifiedIdentity(Provider.GOOGLE, "g-1", null, false));
        UUID legacyApple = service.signIn(apple("a-legacy", null));

        deletion.delete(account);
        deletion.delete(legacyApple);

        verify(appleTokens, never()).revoke(any(), any());
        assertThat(count("user_data.account")).isZero();
    }
}
