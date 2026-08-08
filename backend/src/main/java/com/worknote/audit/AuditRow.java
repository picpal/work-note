package com.worknote.audit;

/** detail = 권한 변경 델타 JSON(없으면 null). target은 사람이 읽는 라벨, detail은 기계 판독용. */
public record AuditRow(long id, String at, String who, String act, String target, String ip, String detail) {}
