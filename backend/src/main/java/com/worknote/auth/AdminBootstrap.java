package com.worknote.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

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

    @Override
    public void run(ApplicationArguments args) {
        if (users.countUsers() > 0) {
            return;   // 이미 사용자 존재 — 부트스트랩 불필요 (멱등)
        }
        if (adminPassword == null || adminPassword.isBlank()) {
            // secure-by-default: 기본 비밀번호 금지 — env 미지정 시 기동 자체를 막는다
            throw new IllegalStateException(
                "server 모드 최초 기동: WORKNOTE_ADMIN_PASSWORD 환경변수로 관리자 비밀번호를 지정하세요");
        }
        users.insert(new UserRow(ADMIN_ID, "admin", null, "관리자", "admin", "active", null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow(ADMIN_ID, salt, PasswordHasher.hash(adminPassword, salt)));
    }
}
