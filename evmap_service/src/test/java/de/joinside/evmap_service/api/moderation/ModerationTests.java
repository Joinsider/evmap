package de.joinside.evmap_service.api.moderation;

import de.joinside.evmap_service.api.comment.CommentController.CommentNotFoundException;
import de.joinside.evmap_service.api.comment.CommentVisibilityAccess;
import de.joinside.evmap_service.support.PostgisDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reporting, blocking and the admin queue against the real schema (ADR 0020). */
class ModerationTests {
    private ModerationService moderation;
    private UUID station;
    private UUID author;
    private UUID reader;
    private UUID comment;

    @BeforeEach
    void setUp() {
        PostgisDatabase.clearMasterData();
        PostgisDatabase.clearUserData();
        moderation = new ModerationService(new ModerationRepository(PostgisDatabase.jdbc()));
        station = PostgisDatabase.insertStation("EnBW Stuttgart", "EnBW", "DE", 48.77, 9.18);
        author = account();
        reader = account();
        comment = comment(author, "Charger was broken");
    }

    private static UUID account() {
        UUID id = UUID.randomUUID();
        PostgisDatabase.jdbc().sql("INSERT INTO user_data.account (id) VALUES (:id)").param("id", id).update();
        return id;
    }

    private UUID comment(UUID account, String body) {
        UUID id = UUID.randomUUID();
        PostgisDatabase.jdbc().sql("INSERT INTO user_data.station_comment (id, station_id, account_id, body) VALUES (:id, :station, :account, :body)")
                .param("id", id).param("station", station).param("account", account).param("body", body).update();
        return id;
    }

    private static long count(String table) {
        return PostgisDatabase.jdbc().sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    @Test
    @DisplayName("a report is kept once per reporter, however often it is sent")
    void reportIsIdempotent() {
        moderation.report(comment, reader, "spam");
        moderation.report(comment, reader, "offensive");

        assertThat(count("user_data.comment_report")).isEqualTo(1);
        assertThat(moderation.openReports()).singleElement().satisfies(reported -> assertThat(reported.reasons()).containsEntry("spam", 1));
    }

    @Test
    @DisplayName("you cannot report your own comment, use an unknown reason or report a comment that does not exist")
    void reportValidation() {
        assertThatThrownBy(() -> moderation.report(comment, author, "spam")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> moderation.report(comment, reader, "rude")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> moderation.report(comment, reader, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> moderation.report(UUID.randomUUID(), reader, "spam")).isInstanceOf(CommentNotFoundException.class);
        assertThat(count("user_data.comment_report")).isZero();
    }

    @Test
    @DisplayName("the queue groups reports per comment with the count per reason, newest first, with the station name")
    void queueGroupsPerComment() {
        UUID second = account();
        UUID other = comment(author, "Other comment");
        moderation.report(other, reader, "wrong");
        moderation.report(comment, reader, "spam");
        moderation.report(comment, second, "spam");

        var queue = moderation.openReports();

        assertThat(queue).extracting(AdminReportsController.ReportedComment::commentId).containsExactly(comment, other);
        assertThat(queue.getFirst().reasons()).containsEntry("spam", 2);
        assertThat(queue.getFirst().stationName()).isEqualTo("EnBW Stuttgart");
        assertThat(queue.getFirst().body()).isEqualTo("Charger was broken");
    }

    @Test
    @DisplayName("dismissing closes the reports and keeps the comment; the comment leaves the queue and stays hidden for its reporter")
    void dismissKeepsComment() {
        moderation.report(comment, reader, "spam");

        moderation.dismiss(comment, author);

        assertThat(moderation.openReports()).isEmpty();
        assertThat(count("user_data.station_comment")).isEqualTo(1);
        assertThat(CommentVisibilityAccess.hiddenFor(reader, station)).containsExactly(comment);
        assertThatThrownBy(() -> moderation.dismiss(comment, author)).isInstanceOf(CommentNotFoundException.class);
    }

    @Test
    @DisplayName("removing the comment deletes it together with its reports")
    void removeDeletesReports() {
        moderation.report(comment, reader, "offensive");

        moderation.removeComment(comment, author);

        assertThat(count("user_data.station_comment")).isZero();
        assertThat(count("user_data.comment_report")).isZero();
        assertThatThrownBy(() -> moderation.removeComment(comment, author)).isInstanceOf(CommentNotFoundException.class);
    }

    @Test
    @DisplayName("blocking through a comment hides that author's comments, on every station, for the blocker only")
    void blockingHidesTheAuthor() {
        UUID otherStation = PostgisDatabase.insertStation("Ionity", "Ionity", "DE", 48.8, 9.2);
        UUID elsewhere = UUID.randomUUID();
        PostgisDatabase.jdbc().sql("INSERT INTO user_data.station_comment (id, station_id, account_id, body) VALUES (:id, :station, :account, 'x')")
                .param("id", elsewhere).param("station", otherStation).param("account", author).update();

        moderation.blockAuthorOf(comment, reader);
        moderation.blockAuthorOf(comment, reader);

        assertThat(count("user_data.account_block")).isEqualTo(1);
        assertThat(CommentVisibilityAccess.hiddenFor(reader, station)).containsExactly(comment);
        assertThat(CommentVisibilityAccess.hiddenFor(reader, otherStation)).containsExactly(elsewhere);
        assertThat(CommentVisibilityAccess.hiddenFor(account(), station)).isEmpty();
    }

    @Test
    @DisplayName("a block is listed without saying whom it concerns, and only its owner can lift it")
    void blocksAreAnonymousAndOwned() {
        moderation.blockAuthorOf(comment, reader);
        var blocks = moderation.blocks(reader);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.getFirst().toString()).doesNotContain(author.toString());
        assertThat(moderation.blocks(author)).isEmpty();
        assertThatThrownBy(() -> moderation.unblock(blocks.getFirst().id(), author)).isInstanceOf(CommentNotFoundException.class);

        moderation.unblock(blocks.getFirst().id(), reader);

        assertThat(moderation.blocks(reader)).isEmpty();
        assertThat(CommentVisibilityAccess.hiddenFor(reader, station)).isEmpty();
    }

    @Test
    @DisplayName("you cannot block yourself or the author of a comment that does not exist")
    void blockValidation() {
        assertThatThrownBy(() -> moderation.blockAuthorOf(comment, author)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> moderation.blockAuthorOf(UUID.randomUUID(), reader)).isInstanceOf(CommentNotFoundException.class);
    }

    @Test
    @DisplayName("deleting an account removes the reports it filed and the blocks it made, and blocks against it")
    void accountDeletionCleansUp() {
        moderation.report(comment, reader, "spam");
        moderation.blockAuthorOf(comment, reader);

        PostgisDatabase.jdbc().sql("DELETE FROM user_data.account WHERE id = :id").param("id", reader).update();
        assertThat(count("user_data.comment_report")).isZero();
        assertThat(count("user_data.account_block")).isZero();

        UUID another = account();
        moderation.blockAuthorOf(comment, another);
        PostgisDatabase.jdbc().sql("DELETE FROM user_data.account WHERE id = :id").param("id", author).update();
        assertThat(count("user_data.account_block")).isZero();
    }
}
