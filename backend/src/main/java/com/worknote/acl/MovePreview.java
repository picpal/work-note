package com.worknote.acl;

import java.util.List;

/** 이동 전/후 노출(접근 집합) 델타 — 스펙 §4.3/§7. added/removed는 사람이 읽는 주체 라벨. */
public record MovePreview(boolean publicBefore, boolean publicAfter,
                          boolean crossSpace, String fromSpace, String toSpace,
                          List<String> added, List<String> removed) {}
