package com.worknote.vault.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateNodeRequest(
    @Size(max = NodeLimits.NAME_MAX, message = "이름이 너무 깁니다") String name,
    @Size(max = NodeLimits.CONTENT_MAX, message = "본문이 너무 깁니다") String content,
    @Size(max = NodeLimits.TAGS_MAX, message = "태그가 너무 많습니다")
    List<@Size(max = NodeLimits.TAG_MAX, message = "태그가 너무 깁니다") String> tags
) {}
