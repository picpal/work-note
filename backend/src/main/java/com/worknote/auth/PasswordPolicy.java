package com.worknote.auth;

/** 비밀번호 정책 단일 출처 — 가입·관리자 생성·초기화·본인 변경·브레이크글래스 모두 이 범위를 따른다. */
public final class PasswordPolicy {
    private PasswordPolicy() {}
    public static final int MIN_LENGTH = 10;
    /**
     * 상한 — 로그인 DTO가 받아주는 최대 길이와 같아야 한다. 여기서만 통과하고 로그인에서 잘리면
     * "설정은 됐는데 로그인은 안 되는" 비밀번호가 만들어진다(브레이크글래스에선 그 상태가 곧 재잠금이다).
     * PBKDF2 장문 DoS 가드를 겸한다.
     */
    public static final int MAX_LENGTH = 128;
}
