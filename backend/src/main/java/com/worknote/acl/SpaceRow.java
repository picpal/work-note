package com.worknote.acl;

/** space 1행. 최상위 폴더 ↔ 소유 팀(teamId NULL = 공용). */
public record SpaceRow(String nodeId, String teamId) {}
