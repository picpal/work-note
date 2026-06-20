package com.worknote.admin.dto;

import com.worknote.auth.UserRow;

/** 관리자 사용자 목록 응답 — UserRow 필드 + 2FA 상태. */
public record UserListResponse(
    String id,
    String emp,
    String email,
    String name,
    String roleId,
    String status,
    String lastLogin,
    boolean totpEnabled
) {
    public static UserListResponse of(UserRow row, boolean totpEnabled) {
        return new UserListResponse(
            row.id(), row.emp(), row.email(), row.name(),
            row.roleId(), row.status(), row.lastLogin(),
            totpEnabled
        );
    }
}
