package com.worknote.share.dto;

import java.util.List;

/** 검증(범위)은 ShareLinkService — DTO는 운반만. */
public record CreateShareRequest(Integer days, Integer maxViews, List<String> pinEmps) {}
