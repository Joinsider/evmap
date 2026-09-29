package de.joinside.evmap_service.api.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminControllerTests {
    private final AdminRepository repository = mock(AdminRepository.class);
    private final AdminController controller = new AdminController(repository);

    @Test
    @DisplayName("clamps the requested number of sync runs to 1..100")
    void clampsLimit() {
        when(repository.syncRuns(org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());

        controller.syncRuns(0);
        controller.syncRuns(5_000);

        verify(repository).syncRuns(1);
        verify(repository).syncRuns(100);
    }

    @Test
    @DisplayName("passes the overview through")
    void overview() {
        AdminController.Overview overview = new AdminController.Overview(1, 2, 3, 4);
        when(repository.overview()).thenReturn(overview);

        assertThat(controller.overview()).isEqualTo(overview);
    }
}
