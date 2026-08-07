package com.worknote.vault.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateNodeRequest(
    @Size(max = NodeLimits.ID_MAX, message = "id가 너무 깁니다") String id,
    @Size(max = NodeLimits.ID_MAX, message = "parentId가 너무 깁니다") String parentId,
    @NotBlank String type,
    @NotBlank @Size(max = NodeLimits.NAME_MAX, message = "이름이 너무 깁니다") String name,
    @Size(max = NodeLimits.CONTENT_MAX, message = "본문이 너무 깁니다") String content
) {}
