package com.worknote.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateRoleRequest(
    @NotBlank @Size(max = 32) @Pattern(regexp = "[a-z][a-z0-9-]*") String id,
    @NotBlank @Size(max = 64) String name,
    @NotNull List<String> caps
) {}
