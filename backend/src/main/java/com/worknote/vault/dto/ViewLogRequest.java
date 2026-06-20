package com.worknote.vault.dto;

/** 노트 조회 감사 핑 본문 — title: 조회 시점의 페이지명(감사 target에 기록).
    권한 변동·이름 변경과 무관하게 "그때 본 그 이름"을 보존한다. 빈 값이면 컨트롤러가 노드 id로 폴백. */
public record ViewLogRequest(String title) {}
