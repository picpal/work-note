package com.worknote.admin.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
    @Size(max = 64) String name,
    @Size(max = 128) String email,
    @Size(max = 32) String roleId,
    @Pattern(regexp = "active|disabled") String status
) {}
