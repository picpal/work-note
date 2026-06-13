package com.worknote.admin.dto;

import com.worknote.auth.PasswordPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
    @NotBlank @Size(max = 64) String emp,
    @NotBlank @Size(max = 64) String name,
    @Size(max = 128) String email,
    @NotBlank @Size(max = 32) String roleId,
    @NotBlank @Size(min = PasswordPolicy.MIN_LENGTH, max = 128) String password
) {}
