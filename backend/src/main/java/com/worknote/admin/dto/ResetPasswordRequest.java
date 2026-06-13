package com.worknote.admin.dto;

import com.worknote.auth.PasswordPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(@NotBlank @Size(min = PasswordPolicy.MIN_LENGTH, max = 128) String password) {}
