package com.worknote.vault;

/** tag 테이블 1행 — 트리 조립 시 N+1 방지용 일괄 조회 결과. */
public record TagRow(String nodeId, String tag) {}
