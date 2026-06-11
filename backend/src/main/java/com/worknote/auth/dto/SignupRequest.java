package com.worknote.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
    @NotBlank @Size(max = 64) String emp,
    @NotBlank @Size(max = 64) String name,
    @NotBlank @Size(min = 8, max = 128) String password,
    @Size(max = 128) String email
) {}
