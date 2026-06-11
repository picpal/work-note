package com.worknote.acl;

/** public_flag 1행. mode: public(폴더 cascade) | exclude(노트 카브아웃). */
public record PublicFlagRow(String nodeId, String mode) {}
