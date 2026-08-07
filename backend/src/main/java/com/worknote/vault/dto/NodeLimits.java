package com.worknote.vault.dto;

/**
 * 노드 입력 상한 — 2026-08-07 보안감사 H-1 조치.
 *
 * <p>저장 경로의 비용(PII 스캔·태그 재작성)이 본문 길이에 비례하므로, 무제한 본문은
 * 그 자체로 DoS 입력이 된다. 정규식 선형화(PiiDetector)가 근본 조치이고 이 상한은 심층 방어다.
 */
public final class NodeLimits {
    private NodeLimits() {}

    /** 본문 100만자 ≈ 마크다운 1MB. 이미지는 첨부(디스크 저장)라 본문에 인라인되지 않는다. */
    public static final int CONTENT_MAX = 1_000_000;
    public static final int NAME_MAX = 200;
    public static final int ID_MAX = 100;
    public static final int TAGS_MAX = 50;
    public static final int TAG_MAX = 50;
}
