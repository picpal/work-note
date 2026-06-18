package com.worknote.auth;

/** 비관리자 사용자 디렉토리 투영 — emp+name만(email·role·status·id 미노출). */
public record DirectoryUser(String emp, String name) {}
