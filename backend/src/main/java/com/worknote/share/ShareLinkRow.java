package com.worknote.share;

/** share_link 행. pinEmps는 JSON 배열 문자열(NULL=전 직원) — 파싱은 서비스/컨트롤러 책임. */
public record ShareLinkRow(String id, String token, String nodeId, String createdBy,
                           String createdAt, String expiresAt, Integer maxViews,
                           int viewCount, String pinEmps, String revokedAt) {}
