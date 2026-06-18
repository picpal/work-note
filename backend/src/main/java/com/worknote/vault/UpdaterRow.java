package com.worknote.vault;

/** node.updated_by(사번) ↔ app_user 조인 1행. 트리 조립 시 "사번(이름)" 라벨 구성용(이름은 선택값). */
public record UpdaterRow(String nodeId, String name, String emp) {}
