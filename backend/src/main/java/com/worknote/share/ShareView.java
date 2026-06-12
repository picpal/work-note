package com.worknote.share;

/** 공유 열람 응답 + 감사 target 구성용 식별자. */
public record ShareView(String linkId, String nodeId, String name, String content, String updatedAt) {}
