package de.joinside.evmap_service.api.auth;

import de.joinside.evmap_service.api.security.CurrentUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountControllerTests {
    @Test
    @DisplayName("deleting the account deletes it for the caller only and expires the session cookie")
    void deletesOwnAccount() throws Exception {
        AccountDeletionService deletion = mock(AccountDeletionService.class);
        UUID id = UUID.randomUUID();
        var response = new AccountController(mock(AccountService.class), deletion,
                new de.joinside.evmap_service.api.security.SessionCookieAccess().cookie()).delete(new CurrentUser(id));

        verify(deletion).delete(id);
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getHeaders().getFirst("Set-Cookie")).contains("Max-Age=0");
    }

    @Test
    @DisplayName("describes the caller's own account: admin flag and linked sign-ins")
    void describesOwnAccount() {
        AccountService accounts = mock(AccountService.class);
        UUID id = UUID.randomUUID();
        when(accounts.isAdmin(id)).thenReturn(true);
        when(accounts.identities(id)).thenReturn(List.of(new AccountRepository.LinkedIdentity("google", "ada@example.org"),
                new AccountRepository.LinkedIdentity("apple", null)));

        AccountController.AccountResponse me = new AccountController(accounts, mock(AccountDeletionService.class), new de.joinside.evmap_service.api.security.SessionCookieAccess().cookie()).me(new CurrentUser(id));

        assertThat(me.id()).isEqualTo(id);
        assertThat(me.admin()).isTrue();
        assertThat(me.identities()).containsExactly(new AccountController.LinkedIdentity("google", "ada@example.org"),
                new AccountController.LinkedIdentity("apple", null));
    }
}
