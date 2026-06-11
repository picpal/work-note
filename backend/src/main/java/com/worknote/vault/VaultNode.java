package com.worknote.vault;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record VaultNode(
    String id, String type, String name, String title,   // folder→name, note→title (둘 중 하나만 non-null)
    Integer position, List<VaultNode> children,           // folder만 children
    List<String> tags, String updated, String content     // note만
) {}
