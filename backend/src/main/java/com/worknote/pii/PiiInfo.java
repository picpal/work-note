package com.worknote.pii;

import java.util.List;

/** 트리 응답에 실리는 노트 PII 요약. status + 유형. null이면 플래그 없음. */
public record PiiInfo(String status, List<String> types) {}
