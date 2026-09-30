package de.joinside.evmap_service.api.auth;

import de.joinside.evmap_service.support.PostgisDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Account linking against the real schema (ADR 0018). The rule these tests pin down is the one that
 * decides whether somebody can take over another person's account: only a <em>verified</em> address,
 * on both sides, links — and never an Apple relay address.
 */
class AccountRepositoryTests {
    private AccountService service;
    private AccountRepository repository;

    @BeforeEach
    void setUp() {
        PostgisDatabase.clearUserData();
        repository = new AccountRepository(PostgisDatabase.jdbc());
        service = new AccountService(repository, new TokenCipher(""));
    }

    private static VerifiedIdentity google(String subject, String email, boolean verified) {
        return new VerifiedIdentity(Provider.GOOGLE, subject, email, verified);
    }

    private static VerifiedIdentity github(String subject, String email, boolean verified) {
        return new VerifiedIdentity(Provider.GITHUB, subject, email, verified);
    }

    @Test
    @DisplayName("a known identity signs in to the same account every time")
    void knownIdentityKeepsItsAccount() {
        UUID first = service.signIn(google("g-1", "ada@example.org", true));

        assertThat(service.signIn(google("g-1", "ada@example.org", true))).isEqualTo(first);
        assertThat(repository.identities(first)).hasSize(1);
    }

    @Test
    @DisplayName("a new account's first identity reuses the account id, like the migrated ones")
    void firstIdentityReusesAccountId() {
        UUID account = service.signIn(google("g-1", null, false));

        assertThat(PostgisDatabase.jdbc().sql("SELECT id FROM user_data.provider_identity WHERE account_id = :id")
                .param("id", account).query(UUID.class).single()).isEqualTo(account);
    }

    @Test
    @DisplayName("a second provider with the same verified address joins the existing account")
    void verifiedAddressLinks() {
        UUID account = service.signIn(google("g-1", "ada@example.org", true));

        assertThat(service.signIn(github("42", "ADA@example.org", true))).isEqualTo(account);
        assertThat(repository.identities(account)).extracting(AccountRepository.LinkedIdentity::provider)
                .containsExactly("google", "github");
    }

    @Test
    @DisplayName("an unverified address on the new identity creates a separate account")
    void unverifiedNewIdentityDoesNotLink() {
        UUID account = service.signIn(google("g-1", "ada@example.org", true));

        assertThat(service.signIn(github("42", "ada@example.org", false))).isNotEqualTo(account);
    }

    @Test
    @DisplayName("an existing identity whose address is unverified is never linked to")
    void unverifiedExistingIdentityIsNotATarget() {
        UUID unverified = service.signIn(github("42", "ada@example.org", false));

        UUID verified = service.signIn(google("g-1", "ada@example.org", true));

        assertThat(verified).isNotEqualTo(unverified);
    }

    @Test
    @DisplayName("Apple private relay addresses link nothing, even when verified")
    void relayAddressDoesNotLink() {
        String relay = "abc123@privaterelay.appleid.com";
        UUID apple = service.signIn(new VerifiedIdentity(Provider.APPLE, "a-1", relay, true));

        assertThat(service.signIn(google("g-1", relay, true))).isNotEqualTo(apple);
    }

    @Test
    @DisplayName("an address verified later is refreshed but does not merge existing accounts")
    void laterVerificationDoesNotMerge() {
        UUID google = service.signIn(google("g-1", "ada@example.org", true));
        UUID github = service.signIn(github("42", "ada@example.org", false));

        assertThat(service.signIn(github("42", "ada@example.org", true))).isEqualTo(github).isNotEqualTo(google);
    }

    @Test
    @DisplayName("the admin flag is false for a new account and read from the database")
    void adminFlag() {
        UUID account = service.signIn(google("g-1", null, false));
        assertThat(service.isAdmin(account)).isFalse();

        PostgisDatabase.jdbc().sql("UPDATE user_data.account SET is_admin = true WHERE id = :id").param("id", account).update();

        assertThat(service.isAdmin(account)).isTrue();
        assertThat(service.isAdmin(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("deleting an account removes its identities and comments")
    void accountDeletionCascades() {
        UUID station = PostgisDatabase.insertStation("EnBW", "EnBW", "DE", 48.77, 9.18);
        UUID account = service.signIn(google("g-1", "ada@example.org", true));
        PostgisDatabase.jdbc().sql("INSERT INTO user_data.station_comment (id, station_id, account_id, body) VALUES (:id, :station, :account, 'ok')")
                .param("id", UUID.randomUUID()).param("station", station).param("account", account).update();

        PostgisDatabase.jdbc().sql("DELETE FROM user_data.account WHERE id = :id").param("id", account).update();

        assertThat(PostgisDatabase.jdbc().sql("SELECT count(*) FROM user_data.provider_identity").query(Long.class).single()).isZero();
        assertThat(PostgisDatabase.jdbc().sql("SELECT count(*) FROM user_data.station_comment").query(Long.class).single()).isZero();
    }
}
