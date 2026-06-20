package com.worknote.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record TotpConfirmRequest(@NotBlank String code) {}
