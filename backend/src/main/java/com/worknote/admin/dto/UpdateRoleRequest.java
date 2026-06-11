package com.worknote.admin.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateRoleRequest(
    @Size(max = 64) String name,
    List<String> caps
) {}
