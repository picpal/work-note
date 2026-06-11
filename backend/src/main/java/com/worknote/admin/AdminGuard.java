package com.worknote.admin;

import com.worknote.acl.PermissionService;
import com.worknote.auth.AuthException;
import com.worknote.auth.UserRow;
import com.worknote.vault.VaultException;
import org.springframework.stereotype.Component;

/** /api/admin/* 공통 가드. local 모드(user=null)는 단일 사용자=관리자라 통과, server 모드는 관리자 caps 필수. */
@Component
public class AdminGuard {

    private final PermissionService perm;

    public AdminGuard(PermissionService perm) {
        this.perm = perm;
    }

    public void requireAdmin(UserRow user) {
        if (user == null) {
            if (!perm.serverMode()) {
                return;
            }
            // server 모드인데 user가 없다 = AuthFilter 우회 경로 — 2차 방어
            throw AuthException.unauthorized("인증이 필요합니다");
        }
        if (!perm.isAdmin(user)) {
            throw VaultException.forbidden("관리자 권한이 필요합니다");
        }
    }
}
