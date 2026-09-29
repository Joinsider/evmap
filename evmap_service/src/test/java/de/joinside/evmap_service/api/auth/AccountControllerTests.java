package de.joinside.evmap_service.api.auth;

import de.joinside.evmap_service.api.security.CurrentUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountControllerTests {
    @Test
    @DisplayName("describes the caller's own account: admin flag and linked sign-ins")
    void describesOwnAccount() {
        AccountService accounts = mock(AccountService.class);
        UUID id = UUID.randomUUID();
        when(accounts.isAdmin(id)).thenReturn(true);
        when(accounts.identities(id)).thenReturn(List.of(new AccountRepository.LinkedIdentity("google", "ada@example.org"),
                new AccountRepository.LinkedIdentity("apple", null)));

        AccountController.AccountResponse me = new AccountController(accounts).me(new CurrentUser(id));

        assertThat(me.id()).isEqualTo(id);
        assertThat(me.admin()).isTrue();
        assertThat(me.identities()).containsExactly(new AccountController.LinkedIdentity("google", "ada@example.org"),
                new AccountController.LinkedIdentity("apple", null));
    }
}
