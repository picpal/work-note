package com.worknote.admin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** 업로드 정책 변경 요청 — 허용 확장자 목록(비어있지 않음) + 파일당 최대 바이트(1 이상). */
public record UploadPolicyRequest(@NotEmpty List<String> allowedExt, @Min(1) long maxBytes) {}
