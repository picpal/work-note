package com.worknote.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record RecoverRequest(@NotBlank String emp) {}
