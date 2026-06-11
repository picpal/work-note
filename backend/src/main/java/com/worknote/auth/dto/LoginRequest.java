package com.worknote.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank String emp, @NotBlank String password) {}
