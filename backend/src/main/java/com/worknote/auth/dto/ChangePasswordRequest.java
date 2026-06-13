package com.worknote.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** 본인 비밀번호 변경 요청. 길이 정책(새 비번 10자)은 서비스에서 재검증 — DTO는 공백만 차단. */
public record ChangePasswordRequest(
    @NotBlank String currentPassword,
    @NotBlank String newPassword) {}
