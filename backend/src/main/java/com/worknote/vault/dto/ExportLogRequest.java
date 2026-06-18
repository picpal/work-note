package com.worknote.vault.dto;

/** 내보내기 감사 핑 본문 — format: pdf|md|copy (그 외는 컨트롤러에서 "기타"로 정규화). */
public record ExportLogRequest(String format) {}
