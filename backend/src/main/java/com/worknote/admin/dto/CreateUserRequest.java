package com.worknote.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
    @NotBlank @Size(max = 64) String emp,
    @NotBlank @Size(max = 64) String name,
    @Size(max = 128) String email,
    @NotBlank @Size(max = 32) String roleId,
    @NotBlank @Size(min = 8, max = 128) String password
) {}
