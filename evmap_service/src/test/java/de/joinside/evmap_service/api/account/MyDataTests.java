package de.joinside.evmap_service.api.account;

import de.joinside.evmap_service.api.security.CurrentUser;
import de.joinside.evmap_service.support.PostgisDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The data export and "my contributions" against the real schema (ADR 0020). */
class MyDataTests {
    private MyDataController controller;
    private UUID station;
    private UUID me;
    private UUID other;

    @BeforeEach
    void setUp() {
        PostgisDatabase.clearMasterData();
        PostgisDatabase.clearUserData();
        controller = new MyDataController(new MyDataRepository(PostgisDatabase.jdbc()));
        station = PostgisDatabase.insertStation("EnBW Stuttgart", "EnBW", "DE", 48.77, 9.18);
        me = account();
        other = account();
        identity(me, "google", "g-1", "ada@example.org", true, null);
        identity(me, "apple", "a-1", null, false, "v1:secret-ciphertext");
        identity(other, "github", "42", "octo@example.org", true, null);
    }

    private static UUID account() {
        UUID id = UUID.randomUUID();
        PostgisDatabase.jdbc().sql("INSERT INTO user_data.account (id) VALUES (:id)").param("id", id).update();
        return id;
    }

    private static void identity(UUID account, String provider, String subject, String email, boolean verified, String refreshToken) {
        PostgisDatabase.jdbc().sql("INSERT INTO user_data.provider_identity (id, account_id, provider, provider_subject, email, email_verified, refresh_token, refresh_token_client_id) "
                        + "VALUES (:id, :account, :provider, :subject, :email, :verified, :token, :client)")
                .param("id", UUID.randomUUID()).param("account", account).param("provider", provider).param("subject", subject)
                .param("email", email).param("verified", verified).param("token", refreshToken).param("client", refreshToken == null ? null : "de.joinside.EVMap").update();
    }

    private UUID comment(UUID account, String body) {
        UUID id = UUID.randomUUID();
        PostgisDatabase.jdbc().sql("INSERT INTO user_data.station_comment (id, station_id, account_id, body, paid_price_cents, experience) VALUES (:id, :station, :account, :body, 490, 'POSITIVE')")
                .param("id", id).param("station", station).param("account", account).param("body", body).update();
        return id;
    }

    @Test
    @DisplayName("the export holds the caller's account, sign-ins, comments, filed reports and blocks — and nothing of anybody else")
    void exportCoversEverythingOfTheCaller() {
        comment(me, "my comment");
        UUID theirs = comment(other, "their comment");
        PostgisDatabase.jdbc().sql("INSERT INTO user_data.comment_report (id, comment_id, reporter_id, reason) VALUES (:id, :comment, :me, 'spam')")
                .param("id", UUID.randomUUID()).param("comment", theirs).param("me", me).update();
        PostgisDatabase.jdbc().sql("INSERT INTO user_data.account_block (id, blocker_id, blocked_id) VALUES (:id, :me, :other)")
                .param("id", UUID.randomUUID()).param("me", me).param("other", other).update();

        var response = controller.export(new CurrentUser(me));
        MyDataController.DataExport export = response.getBody();

        assertThat(response.getHeaders().getFirst("Content-Disposition")).startsWith("attachment; filename=\"evmap-export-");
        assertThat(export.account().id()).isEqualTo(me);
        assertThat(export.identities()).extracting(MyDataController.IdentityData::provider).containsExactly("google", "apple");
        assertThat(export.identities().getFirst().email()).isEqualTo("ada@example.org");
        assertThat(export.comments()).extracting(MyDataController.CommentData::body).containsExactly("my comment");
        assertThat(export.comments().getFirst().stationName()).isEqualTo("EnBW Stuttgart");
        assertThat(export.reportsFiled()).singleElement().satisfies(report -> assertThat(report.reason()).isEqualTo("spam"));
        assertThat(export.blocks()).hasSize(1);
        String everything = export.toString();
        assertThat(everything).doesNotContain("their comment").doesNotContain("octo@example.org").doesNotContain(other.toString())
                .doesNotContain("secret-ciphertext");
    }

    @Test
    @DisplayName("the export covers every table that holds the account's data")
    void exportKnowsEveryUserDataTable() {
        // A table added to user_data must be added to the export (and be deleted with the account) or
        // this fails. Tables that hold no personal data of their own belong in the second list.
        List<String> tables = PostgisDatabase.jdbc().sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'user_data' "
                + "AND table_name NOT IN ('databasechangelog', 'databasechangeloglock') ORDER BY table_name").query(String.class).list();

        assertThat(tables).containsExactlyInAnyOrder("account", "provider_identity", "station_comment", "comment_report", "account_block", "favorite_station", "station_report");
    }

    @Test
    @DisplayName("the export holds the caller's favorites and station reports with their own text, and nobody else's")
    void exportCoversFavoritesAndStationReports() {
        PostgisDatabase.jdbc().sql("INSERT INTO user_data.favorite_station (account_id, station_id) VALUES (:me, :station), (:other, :station)")
                .param("me", me).param("other", other).param("station", station).update();
        stationReport(me, "wrong_power", "It is 11 kW, not 22");
        stationReport(other, "gone", "their note");

        MyDataController.DataExport export = controller.export(new CurrentUser(me)).getBody();

        assertThat(export.favorites()).singleElement().satisfies(favorite -> {
            assertThat(favorite.stationId()).isEqualTo(station);
            assertThat(favorite.stationName()).isEqualTo("EnBW Stuttgart");
        });
        assertThat(export.stationReports()).singleElement().satisfies(report -> {
            assertThat(report.reason()).isEqualTo("wrong_power");
            assertThat(report.note()).isEqualTo("It is 11 kW, not 22");
            assertThat(report.status()).isEqualTo("open");
        });
        assertThat(export.toString()).doesNotContain("their note");
        assertThat(controller.contributions(new CurrentUser(me)).stationReports()).hasSize(1);
        assertThat(controller.contributions(new CurrentUser(other)).stationReports()).singleElement()
                .satisfies(report -> assertThat(report.note()).isEqualTo("their note"));
    }

    private void stationReport(UUID reporter, String reason, String note) {
        PostgisDatabase.jdbc().sql("INSERT INTO user_data.station_report (id, station_id, reporter_id, reason, note) VALUES (:id, :station, :reporter, :reason, :note)")
                .param("id", UUID.randomUUID()).param("station", station).param("reporter", reporter).param("reason", reason).param("note", note).update();
    }

    @Test
    @DisplayName("contributions list the caller's own comments and reports only")
    void contributions() {
        comment(me, "mine");
        UUID theirs = comment(other, "theirs");
        PostgisDatabase.jdbc().sql("INSERT INTO user_data.comment_report (id, comment_id, reporter_id, reason) VALUES (:id, :comment, :me, 'wrong')")
                .param("id", UUID.randomUUID()).param("comment", theirs).param("me", me).update();

        MyDataController.Contributions mine = controller.contributions(new CurrentUser(me));

        assertThat(mine.comments()).extracting(MyDataController.CommentData::body).containsExactly("mine");
        assertThat(mine.reports()).singleElement().satisfies(report -> {
            assertThat(report.reason()).isEqualTo("wrong");
            assertThat(report.stationName()).isEqualTo("EnBW Stuttgart");
        });
        assertThat(controller.contributions(new CurrentUser(other)).reports()).isEmpty();
    }
}
