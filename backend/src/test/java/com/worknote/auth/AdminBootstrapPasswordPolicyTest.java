package com.worknote.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * 부트스트랩 비밀번호가 정책 범위를 벗어나면 <b>기동을 세운다</b>.
 *
 * <p>여기서 통과시키면 최초 배치가 그대로 잠긴다: 해시는 저장되는데 {@link com.worknote.auth.dto.LoginRequest}가
 * {@code @Size(max = PasswordPolicy.MAX_LENGTH)}로 거부하므로 아무도 로그인할 수 없고, 로그인할 수 없으니
 * 아무도 고쳐 줄 수 없다. 스프링을 띄우지 않는 순수 단위 테스트다 — 컨텍스트가 뜨면 부트스트랩이 이미 돌아버린다.
 */
class AdminBootstrapPasswordPolicyTest {

    /** countUsers()==0 이어야 부트스트랩이 실제로 진행돼 검증에 도달한다(사용자가 있으면 멱등 스킵). */
    private static AdminBootstrap bootstrapWith(String password) {
        UserMapper users = mock(UserMapper.class);
        when(users.countUsers()).thenReturn(0);
        return new AdminBootstrap(users, password);
    }

    private static void run(AdminBootstrap bootstrap) {
        bootstrap.run(new DefaultApplicationArguments());
    }

    @Test
    void tooLongPasswordHaltsStartupInsteadOfLockingTheFirstDeployment() {
        String tooLong = "a".repeat(PasswordPolicy.MAX_LENGTH + 1);
        assertThatThrownBy(() -> run(bootstrapWith(tooLong)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(String.valueOf(PasswordPolicy.MAX_LENGTH))
            // 비밀번호 자체가 예외 메시지·스택트레이스에 실리면 안 된다
            .hasMessageNotContaining(tooLong);
    }

    @Test
    void tooShortPasswordHaltsStartup() {
        String tooShort = "a".repeat(PasswordPolicy.MIN_LENGTH - 1);
        assertThatThrownBy(() -> run(bootstrapWith(tooShort)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(String.valueOf(PasswordPolicy.MIN_LENGTH))
            .hasMessageNotContaining(tooShort);
    }

    @Test
    void boundaryLengthsAreAccepted() {
        assertThatCode(() -> run(bootstrapWith("a".repeat(PasswordPolicy.MIN_LENGTH))))
            .doesNotThrowAnyException();
        assertThatCode(() -> run(bootstrapWith("a".repeat(PasswordPolicy.MAX_LENGTH))))
            .doesNotThrowAnyException();
    }
}
