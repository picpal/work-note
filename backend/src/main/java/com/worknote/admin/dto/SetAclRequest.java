package com.worknote.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 노드 단위 ACL replace-all 요청.
 *
 * <p>entries 상한은 감사 델타(audit_log.detail)가 항상 온전히 들어가게 하는 장치다.
 * 크기 기반 델타 절단은 "큰 변경을 만들면 증거가 사라지는" 인멸 경로가 되므로 쓰지 않고,
 * 대신 입력을 막는다 — 상한 초과는 400이라 변경도 기록 누락도 발생하지 않는다.
 * 실사용은 노드당 주체 수십 개 수준이라 200은 넉넉한 여유값.
 */
public record SetAclRequest(@NotNull @Size(max = SetAclRequest.MAX_ENTRIES) List<@Valid AclEntryRequest> entries) {

    public static final int MAX_ENTRIES = 200;
}
