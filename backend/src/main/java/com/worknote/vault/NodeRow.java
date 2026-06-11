package com.worknote.vault;

/** node 테이블 1행. tree 조립은 서비스 계층에서. */
public record NodeRow(
    String id, String parentId, String type, String name,
    int position, String content, String updatedAt,
    String deletedAt, String deletedBy
) {}
