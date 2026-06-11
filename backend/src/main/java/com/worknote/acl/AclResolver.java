package com.worknote.acl;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 스펙 §5 해석 알고리즘의 순수 부분 — DB 무관, 정적. 입출력은 PermissionService가 만든다. */
public final class AclResolver {

    /** 관리자 판정 기준: 역할 caps가 admin.* 5종 전부 포함 (스펙 §3.1 — 관리자는 ACL/deny 우회). */
    public static final Set<String> ADMIN_CAPS = Set.of(
        "admin.users", "admin.permissions", "admin.roles", "admin.security", "admin.audit");

    private AclResolver() {}

    /**
     * 한 주체의 nearest-explicit + deny-sticky(§5.1) grant.
     * 체인 어딘가에 그 주체의 deny가 있으면 더 가까운 allow와 무관하게 "deny"
     * (한 주체 안에서 deny 아래 재허용 없음). deny가 없으면 가장 가까운 명시 entry.
     * @param chain 노드 자신→루트 순 조상 체인
     * @param grantsByNode 그 주체의 nodeId→grant 엔트리
     * @return deny가 체인에 있으면 "deny", 아니면 가장 가까운 명시 grant, 없으면 null
     */
    public static String nearestExplicit(List<String> chain, Map<String, String> grantsByNode) {
        String nearest = null;
        for (String nodeId : chain) {
            String grant = grantsByNode.get(nodeId);
            if (grant == null) {
                continue;
            }
            if ("deny".equals(grant)) {
                return "deny";
            }
            if (nearest == null) {
                nearest = grant;
            }
        }
        return nearest;
    }

    /** 다중 주체 deny-우선 합집합: deny 하나라도 있으면 DENY, 없으면 allow의 최대치. */
    public static Access combine(Collection<String> nearestGrants) {
        boolean edit = false;
        boolean read = false;
        for (String grant : nearestGrants) {
            if (grant == null) continue;
            switch (grant) {
                case "deny" -> { return Access.DENY; }
                case "edit" -> edit = true;
                case "read" -> read = true;
                // DB CHECK가 소문자 3종을 보장 — 도달하면 버그. 조용한 무시는 fail-open이라 loud failure.
                default -> throw new IllegalArgumentException("알 수 없는 grant: " + grant);
            }
        }
        return edit ? Access.EDIT : read ? Access.READ : Access.NONE;
    }

    /**
     * 체인에서 가장 가까운 public_flag가 'public'이면 true ('exclude'가 더 가까우면 false).
     * 호출 순서 계약: combine 결과가 DENY가 아닐 때만 조회할 것 —
     * deny > public 우선순위(§5.1)는 호출자(PermissionService) 책임이다.
     */
    public static boolean publicRead(List<String> chain, Map<String, String> flagsByNode) {
        for (String nodeId : chain) {
            String mode = flagsByNode.get(nodeId);
            if (mode != null) {
                return "public".equals(mode);
            }
        }
        return false;
    }
}
