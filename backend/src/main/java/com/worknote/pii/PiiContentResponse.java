package com.worknote.pii;

import java.util.List;

/** 관리자 PII 노트 본문 열람 응답 — 본문 + 매치 라인 위치. */
public record PiiContentResponse(String nodeId, String title, String content, List<MatchLine> matches) {
    /** line=1-based, col=라인 내 시작 오프셋(0-based). */
    public record MatchLine(String type, int line, int col, String value) {}
}
