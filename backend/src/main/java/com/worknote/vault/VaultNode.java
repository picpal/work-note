package com.worknote.vault;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.worknote.pii.PiiInfo;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record VaultNode(
    String id, String type, String name, String title,   // folder→name, note→title (둘 중 하나만 non-null)
    Integer position, List<VaultNode> children,           // folder만 children
    List<String> tags, String updated, String content,    // note만
    PiiInfo pii,                                           // note만(플래그 있을 때) — null이면 직렬화 생략
    String updatedBy                                       // note만: "사번(이름)" 라벨 — 미해석 시 null(직렬화 생략)
) {}
