package com.worknote.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RecoverVerifyRequest(
    @NotBlank @Size(max = 64) String emp,
    @NotBlank @Size(min = 8, max = 8) String code
) {}
