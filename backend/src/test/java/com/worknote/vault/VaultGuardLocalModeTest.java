package com.worknote.vault;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.*;

/** local 모드(worknote.mode 미지정) — user=null은 전부 bypass. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared")
class VaultGuardLocalModeTest {
    @Autowired VaultGuard guard;

    @Test
    void nullUserBypassesAllGuards() {
        assertThatCode(() -> guard.requireEdit(null, "any")).doesNotThrowAnyException();
        assertThatCode(() -> guard.requireCreate(null, null)).doesNotThrowAnyException();
        assertThatCode(() -> guard.requirePurge(null)).doesNotThrowAnyException();
        assertThat(guard.readableIds(null)).isNull();      // null = 무필터
        assertThat(guard.trashFilter(null)).isNull();      // null = 전체
        assertThat(guard.who(null)).isEqualTo("local");    // deleted_by 값
    }
}
