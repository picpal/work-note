package com.worknote.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * 부트스트랩 비밀번호가 정책 범위를 벗어나면 <b>기동을 세운다</b>. 상한과 하한은 세우는 이유가 다르다.
 *
 * <ul>
 *   <li><b>상한</b> — 통과시키면 최초 배치가 그대로 잠긴다: 해시는 저장되는데
 *       {@link com.worknote.auth.dto.LoginRequest}의 {@code @Size(max = PasswordPolicy.MAX_LENGTH)}가
 *       거부하므로 아무도 로그인할 수 없고, 로그인할 수 없으니 아무도 고쳐 줄 수 없다.</li>
 *   <li><b>하한</b> — 로그인이 막히지는 <b>않는다</b>({@code LoginRequest}에 하한이 없다). 세우는 이유는
 *       정책 일관성이다: 가입·관리자 생성·초기화·본인 변경이 모두 이 하한을 지키는데 최초 관리자만
 *       예외일 이유가 없다. 두 사유를 뭉뚱그리면 운영자가 엉뚱한 곳을 고치게 된다.</li>
 * </ul>
 *
 * <p>스프링을 띄우지 않는 순수 단위 테스트다 — 컨텍스트가 뜨면 부트스트랩이 이미 돌아버린다.
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
            .as("상한의 사유는 '로그인 자체가 막힌다'여야 한다")
            .hasMessageContaining("로그인")
            // 비밀번호 자체가 예외 메시지·스택트레이스에 실리면 안 된다
            .hasMessageNotContaining(tooLong);
    }

    /**
     * 하한 메시지는 <b>로그인 불가를 사유로 대면 안 된다</b> — {@code LoginRequest}에 하한이 없어서 9자
     * 비밀번호는 로그인 단계를 그냥 지나간다. 틀린 사유를 주면 운영자가 엉뚱한 곳을 고친다.
     */
    @Test
    void tooShortPasswordHaltsStartupForPolicyConsistencyNotForLoginRejection() {
        String tooShort = "a".repeat(PasswordPolicy.MIN_LENGTH - 1);
        assertThatThrownBy(() -> run(bootstrapWith(tooShort)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(String.valueOf(PasswordPolicy.MIN_LENGTH))
            .as("하한을 '로그인이 거부된다'로 설명하면 사실과 다르다 — LoginRequest에는 하한이 없다")
            .hasMessageNotContaining("로그인")
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
