package com.worknote.template;

/**
 * 템플릿 입력 상한 — NodeLimits와 같은 역할.
 *
 * <p>템플릿은 양식이라 노트 본문(1,000,000자)보다 짧다. 개인 개수 상한은 목록 UI가
 * 스크롤 지옥이 되지 않게 하는 실용적 제한이자 남용 방지책이다.
 */
public final class TemplateLimits {
    private TemplateLimits() {}

    public static final int NAME_MAX = 50;
    public static final int BODY_MAX = 100_000;
    public static final int PER_OWNER_MAX = 50;
}
