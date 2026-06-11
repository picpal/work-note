package com.worknote.auth;

/** role 1행. caps = JSON 배열 문자열 (파싱은 후속 태스크 RoleCaps). */
public record RoleRow(String id, String name, int system, String caps) {}
