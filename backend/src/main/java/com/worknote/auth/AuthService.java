package com.worknote.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/** 로그인 검증·last_login 스탬프. 세션 부여는 AuthController(후속 태스크). */
@Service
public class AuthService {

    private static final String BAD_CREDENTIALS = "사번 또는 비밀번호가 올바르지 않습니다";

    private final UserMapper users;
    private final RoleCaps roleCaps;
    private final Clock clock;

    public AuthService(UserMapper users, RoleCaps roleCaps, Clock clock) {
        this.users = users;
        this.roleCaps = roleCaps;
        this.clock = clock;
    }

    public record AuthUser(UserRow user, Set<String> caps) {}

    @Transactional
    public AuthUser login(String emp, String password) {
        UserRow user = users.findByEmp(emp);
        if (user == null) {
            throw AuthException.unauthorized(BAD_CREDENTIALS);
        }
        CredentialRow cred = users.findCredential(user.id());
        if (cred == null || !PasswordHasher.verify(password, cred.salt(), cred.passwordHash())) {
            throw AuthException.unauthorized(BAD_CREDENTIALS);
        }
        // 비밀번호 검증 후에만 상태 노출 — 미인증 상대에게 계정 상태를 알리지 않음
        if (!"active".equals(user.status())) {
            throw AuthException.forbidden("활성화되지 않은 계정입니다 (상태: " + user.status() + ")");
        }
        users.stampLastLogin(user.id(),
            LocalDateTime.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return new AuthUser(user, roleCaps.of(user.roleId()));
    }

    public Set<String> caps(UserRow user) {
        return roleCaps.of(user.roleId());
    }
}
