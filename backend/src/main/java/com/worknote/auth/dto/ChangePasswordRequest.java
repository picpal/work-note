package com.worknote.auth.dto;

import com.worknote.auth.PasswordPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 본인 비밀번호 변경 요청. 하한(10자)은 서비스에서 재검증 — DTO는 공백과 상한만 막는다.
 * 상한이 둘 다 붙는 이유: 현재 비밀번호도 PBKDF2로 검증되므로 장문 DoS 표면이 같고,
 * 로그인 DTO 상한(=PasswordPolicy.MAX_LENGTH)을 넘는 새 비밀번호는 설정돼도 로그인이 안 된다.
 */
public record ChangePasswordRequest(
    @NotBlank @Size(max = PasswordPolicy.MAX_LENGTH) String currentPassword,
    @NotBlank @Size(max = PasswordPolicy.MAX_LENGTH) String newPassword) {}
