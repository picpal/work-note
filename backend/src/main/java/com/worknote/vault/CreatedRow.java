package com.worknote.vault;

/** node 생성일 조회 결과(id ↔ created_at). 트리 조립 시 createdByNode 맵으로 사용 — NodeRow 무변경 유지용. */
public record CreatedRow(String nodeId, String created) {}
