package com.worknote.admin;

import com.worknote.acl.PermissionService;
import com.worknote.auth.AuthException;
import com.worknote.auth.UserRow;
import com.worknote.vault.VaultException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminGuardTest {

    private final PermissionService perm = mock(PermissionService.class);
    private final AdminGuard guard = new AdminGuard(perm);

    private static final UserRow USER = new UserRow("u1", "10001", null, "홍길동", "operator", "active", null);

    @Test
    void localMode_nullUser_passes() {
        when(perm.serverMode()).thenReturn(false);
        assertThatCode(() -> guard.requireAdmin(null)).doesNotThrowAnyException();
    }

    @Test
    void serverMode_nullUser_unauthorized() {
        when(perm.serverMode()).thenReturn(true);
        assertThatThrownBy(() -> guard.requireAdmin(null)).isInstanceOf(AuthException.class);
    }

    @Test
    void serverMode_nonAdmin_forbidden() {
        when(perm.serverMode()).thenReturn(true);
        when(perm.isAdmin(USER)).thenReturn(false);
        assertThatThrownBy(() -> guard.requireAdmin(USER))
            .isInstanceOf(VaultException.class)
            .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((VaultException) e).status())
                .isEqualTo(VaultException.Status.FORBIDDEN));
    }

    @Test
    void serverMode_admin_passes() {
        when(perm.serverMode()).thenReturn(true);
        when(perm.isAdmin(USER)).thenReturn(true);
        assertThatCode(() -> guard.requireAdmin(USER)).doesNotThrowAnyException();
    }
}
