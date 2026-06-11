package com.worknote.auth;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AdminBootstrapUnitTest {

    @Test
    void blankPasswordFailsFastWhenNoUsers() {
        UserMapper users = mock(UserMapper.class);
        when(users.countUsers()).thenReturn(0);
        AdminBootstrap boot = new AdminBootstrap(users, "");
        assertThatThrownBy(() -> boot.run(null)).isInstanceOf(IllegalStateException.class);
        verify(users, never()).insert(any());   // fail-fast 시 부분 쓰기 없음
    }

    @Test
    void existingUsersSkipBootstrap() throws Exception {
        UserMapper users = mock(UserMapper.class);
        when(users.countUsers()).thenReturn(3);
        new AdminBootstrap(users, "").run(null);   // 사용자 존재 → 예외 없이 skip
        verify(users, never()).insert(any());
        verify(users, never()).insertCredential(any());
    }
}
