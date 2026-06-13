package com.worknote.auth;

/** 비밀번호 정책 단일 출처 — 가입·관리자 생성·초기화·본인 변경 모두 이 최소 길이를 따른다. */
public final class PasswordPolicy {
    private PasswordPolicy() {}
    public static final int MIN_LENGTH = 10;
}
