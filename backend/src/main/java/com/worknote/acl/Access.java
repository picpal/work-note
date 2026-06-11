package com.worknote.acl;

/**
 * 다중 주체 합산 결과. DENY는 절대(allow로 못 뒤집음 — 스펙 §5.1).
 * 주의: ordinal 비교 금지(DENY가 최대 ordinal) — 반드시 allowsRead/allowsEdit 헬퍼를 쓸 것.
 */
public enum Access {
    NONE, READ, EDIT, DENY;

    public boolean allowsRead() {
        return this == READ || this == EDIT;
    }

    public boolean allowsEdit() {
        return this == EDIT;
    }
}
