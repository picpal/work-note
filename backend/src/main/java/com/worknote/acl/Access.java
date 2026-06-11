package com.worknote.acl;

/** 다중 주체 합산 결과. DENY는 절대(allow로 못 뒤집음 — 스펙 §5.1). */
public enum Access { NONE, READ, EDIT, DENY }
