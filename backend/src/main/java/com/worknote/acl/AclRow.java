package com.worknote.acl;

/** acl 1행. principalType: user|team|all (@all 센티넬), grantType: read|edit|deny. */
public record AclRow(String principalType, String principalId, String nodeId, String grantType) {}
