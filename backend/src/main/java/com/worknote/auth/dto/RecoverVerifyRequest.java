package com.worknote.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record RecoverVerifyRequest(@NotBlank String emp, @NotBlank String code) {}
