package com.worknote.share;

/** 관리자 활성 링크 목록 행 — node JOIN. nodeDeletedAt != null = suspend(휴지통). */
public record ActiveShareRow(String id, String token, String nodeId, String nodeName,
                             String nodeDeletedAt, String createdBy, String createdAt,
                             String expiresAt, Integer maxViews, int viewCount, String pinEmps) {}
