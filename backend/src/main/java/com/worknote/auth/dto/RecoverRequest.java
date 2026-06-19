package com.worknote.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RecoverRequest(@NotBlank @Size(max = 64) String emp) {}
