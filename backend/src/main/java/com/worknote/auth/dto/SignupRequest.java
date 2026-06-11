package com.worknote.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
    // \S+ — 사번에 공백·제어문자 불허 (관리자 승인 큐에서 기존 계정 위장 신청 방지)
    @NotBlank @Size(max = 64) @Pattern(regexp = "\\S+") String emp,
    @NotBlank @Size(max = 64) String name,
    @NotBlank @Size(min = 8, max = 128) String password,
    @Size(max = 128) String email
) {}
