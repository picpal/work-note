package com.worknote.vault.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateNodeRequest(
    String id,
    String parentId,
    @NotBlank String type,
    @NotBlank String name,
    String content
) {}
