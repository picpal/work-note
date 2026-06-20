package com.worknote.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record TotpVerifyRequest(@NotBlank String code) {}
