package com.worknote.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** server 모드 최초 기동: 사용자 0명이면 WORKNOTE_ADMIN_PASSWORD로 관리자 생성. 없으면 fail-fast. */
@Component
@ConditionalOnProperty(name = "worknote.mode", havingValue = "server")
public class AdminBootstrap implements ApplicationRunner {

    public static final String ADMIN_ID = "u-admin";

    private final UserMapper users;
    private final String adminPassword;

    public AdminBootstrap(UserMapper users, @Value("${worknote.admin-password:}") String adminPassword) {
        this.users = users;
        this.adminPassword = adminPassword;
    }

    // @Transactional: user/credential insert를 원자화 — 중간 크래시 시 credential 없는 u-admin이
    // 남으면 countUsers>0 스킵으로 영구 로그인 불가가 되기 때문.
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.countUsers() > 0) {
            return;   // 이미 사용자 존재 — 부트스트랩 불필요 (멱등)
        }
        if (adminPassword == null || adminPassword.isBlank()) {
            // secure-by-default: 기본 비밀번호 금지 — env 미지정 시 기동 자체를 막는다
            throw new IllegalStateException(
                "server 모드 최초 기동: WORKNOTE_ADMIN_PASSWORD 환경변수로 관리자 비밀번호를 지정하세요");
        }
        // 정책 범위를 벗어나면 세운다. 상한과 하한은 세우는 이유가 다르므로 문구도 나눈다 —
        // 운영자가 받는 것은 "무엇을 고쳐야 하는가"이고, 틀린 이유를 주면 엉뚱한 곳을 고친다.
        // 비밀번호 자체는 어느 쪽 메시지에도 넣지 않는다(스택트레이스에 실린다).
        if (adminPassword.length() > PasswordPolicy.MAX_LENGTH) {
            // 통과시키면 최초 배치가 그대로 잠긴다: 해시는 저장되는데 LoginRequest의
            // @Size(max = MAX_LENGTH)가 거부하므로 아무도 로그인할 수 없고, 로그인할 수 없으니
            // 아무도 고쳐 줄 수 없다 — 브레이크글래스가 존재하는 그 상태를 부트스트랩이 만드는 셈이다.
            throw new IllegalStateException(
                "WORKNOTE_ADMIN_PASSWORD 가 " + PasswordPolicy.MAX_LENGTH + "자를 넘습니다(지정된 값은 "
                    + adminPassword.length() + "자) — 이대로 두면 해시는 저장되지만 로그인 단계에서 거부되어"
                    + " 아무도 로그인할 수 없고, 그러면 아무도 고쳐 줄 수 없습니다");
        }
        if (adminPassword.length() < PasswordPolicy.MIN_LENGTH) {
            // 이쪽은 로그인이 막히지는 않는다(LoginRequest에 하한이 없다). 세우는 이유는 정책 일관성이다 —
            // 가입·관리자 생성·초기화·본인 변경이 모두 이 하한을 지키는데 최초 관리자만 예외일 이유가 없다.
            throw new IllegalStateException(
                "WORKNOTE_ADMIN_PASSWORD 가 " + PasswordPolicy.MIN_LENGTH + "자보다 짧습니다(지정된 값은 "
                    + adminPassword.length() + "자) — 가입·관리자 생성·초기화·본인 변경이 모두 지키는"
                    + " 최소 길이이므로 최초 관리자도 같은 하한을 지켜야 합니다");
        }
        // 느린 PBKDF2 해시는 insert 전에 미리 계산 — 두 insert 사이 체류 시간 최소화
        String salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash(adminPassword, salt);
        users.insert(new UserRow(ADMIN_ID, "admin", null, "관리자", "admin", "active", null));
        users.insertCredential(new CredentialRow(ADMIN_ID, salt, hash));
    }
}
