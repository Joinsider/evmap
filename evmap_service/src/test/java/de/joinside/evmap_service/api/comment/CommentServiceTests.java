package de.joinside.evmap_service.api.comment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Comments belong to an account (ADR 0018): only its own account may change or delete one. */
class CommentServiceTests {
    private final CommentRepository repository = mock(CommentRepository.class);
    private final CommentService service = new CommentService(repository);
    private final UUID station = UUID.randomUUID();
    private final UUID author = UUID.randomUUID();

    private static CommentController.CommentRequest request(String body) {
        return new CommentController.CommentRequest(body, 4_900, "POSITIVE");
    }

    @Test
    @DisplayName("a new comment is stored for the author's account and marked as theirs")
    void createsForTheAccount() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CommentController.CommentResponse created = service.create(station, author, request("  Works fine  "));

        assertThat(created.body()).isEqualTo("Works fine");
        assertThat(created.ownedByCurrentUser()).isTrue();
    }

    @Test
    @DisplayName("the list marks only the caller's own comments, and none for anonymous readers")
    void marksOwnComments() {
        StationComment own = new StationComment(station, author, request("mine"));
        StationComment other = new StationComment(station, UUID.randomUUID(), request("theirs"));
        when(repository.findByStationIdOrderByCreatedAtDesc(station)).thenReturn(List.of(own, other));

        assertThat(service.list(station, author)).extracting(CommentController.CommentResponse::ownedByCurrentUser).containsExactly(true, false);
        assertThat(service.list(station, null)).extracting(CommentController.CommentResponse::ownedByCurrentUser).containsExactly(false, false);
    }

    @Test
    @DisplayName("an update applies only to a comment of the same account")
    void updatesOwnComment() {
        StationComment own = new StationComment(station, author, request("old"));
        when(repository.findByIdAndAccountId(own.id, author)).thenReturn(Optional.of(own));

        assertThat(service.update(own.id, author, request("new")).body()).isEqualTo("new");

        UUID stranger = UUID.randomUUID();
        when(repository.findByIdAndAccountId(own.id, stranger)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(own.id, stranger, request("hijacked")))
                .isInstanceOf(CommentController.CommentNotFoundException.class);
        assertThatThrownBy(() -> service.delete(own.id, stranger)).isInstanceOf(CommentController.CommentNotFoundException.class);
    }

    @Test
    @DisplayName("deleting removes the account's own comment")
    void deletesOwnComment() {
        StationComment own = new StationComment(station, author, request("bye"));
        when(repository.findByIdAndAccountId(own.id, author)).thenReturn(Optional.of(own));

        service.delete(own.id, author);

        verify(repository).delete(own);
    }

    @Test
    @DisplayName("blank, oversized or negatively priced comments are refused before storing")
    void rejectsInvalid() {
        assertThatThrownBy(() -> service.create(station, author, request(" "))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(station, author, request("x".repeat(2001)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(station, author, new CommentController.CommentRequest("ok", -1, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }
}
