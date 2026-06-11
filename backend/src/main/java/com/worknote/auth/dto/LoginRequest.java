package com.worknote.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 유일한 무인증 쓰기 경로 — 길이 캡으로 audit who 오염·PBKDF2 장문 DoS 가드. */
public record LoginRequest(
    @NotBlank @Size(max = 64) String emp,
    @NotBlank @Size(max = 128) String password
) {}
