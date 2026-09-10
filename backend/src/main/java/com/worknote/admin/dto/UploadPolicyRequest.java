package com.worknote.admin.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 업로드 정책 변경 요청.
 *
 * <p>maxBytes 범위(1 ~ 64MB)와 확장자 표기는 여기서 선언적으로 막지 않고
 * SettingService → UploadPolicy.validate*가 422 + 한국어 사유로 돌려준다.
 * 검증 경로를 한 곳으로 모아 "왜 거부됐는지"가 항상 같은 형식으로 나가게 하기 위함.
 */
public record UploadPolicyRequest(@NotEmpty(message = "허용 확장자를 1개 이상 지정하세요") List<String> allowedExt,
                                  long maxBytes) {}
