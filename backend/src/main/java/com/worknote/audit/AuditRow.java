package com.worknote.audit;

public record AuditRow(long id, String at, String who, String act, String target, String ip) {}
