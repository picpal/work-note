package com.worknote.acl;

import com.worknote.auth.RoleCaps;
import com.worknote.auth.UserRow;
import com.worknote.vault.NodeRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 스펙 §5 해석기 — DB에서 ACL·팀·public을 읽어 AclResolver로 합산.
 * local 모드(user=null)는 전체 허용, server 모드에서 user=null은 차단(방어).
 * pending/disabled 차단은 AuthFilter(매 요청 DB 재조회)가 담당 —
 * 비-HTTP 경로에서 직접 호출 시 status 검사는 호출자 책임.
 */
@Service
public class PermissionService {

    private final AclMapper acl;
    private final TeamMapper teams;
    private final RoleCaps roleCaps;
    private final boolean serverMode;

    public PermissionService(AclMapper acl, TeamMapper teams, RoleCaps roleCaps,
                             @Value("${worknote.mode:local}") String mode) {
        this.acl = acl;
        this.teams = teams;
        this.roleCaps = roleCaps;
        this.serverMode = "server".equals(mode);
    }

    public boolean serverMode() {
        return serverMode;
    }

    public boolean isAdmin(UserRow user) {
        return roleCaps.of(user.roleId()).containsAll(AclResolver.ADMIN_CAPS);
    }

    public boolean roleHas(UserRow user, String cap) {
        return roleCaps.of(user.roleId()).contains(cap);
    }

    /** read(U,N) — 스펙 §5.2. (공유 링크는 다음 계획) */
    @Transactional(readOnly = true)
    public boolean canRead(UserRow user, String nodeId) {
        if (user == null) return !serverMode;
        if (isAdmin(user)) return true;
        if (nodeId == null) return false;   // 미존재/null 노드는 default-deny (canEdit과 대칭 — admin 우회는 위에서 처리)
        List<String> chain = acl.ancestorChain(nodeId);
        if (chain.isEmpty()) return false;  // 미존재 nodeId는 default-deny — IN-리스트 비지 않음 계약 enforce
        Access access = resolveAcl(user, chain);
        if (access == Access.DENY) return false;
        if (access.allowsRead()) return roleHas(user, "res.read");
        Map<String, String> flags = flagMap(acl.findPublicFlagsForNodes(chain));
        return AclResolver.publicRead(chain, flags) && roleHas(user, "res.read");
    }

    /** edit(U,N) — 스펙 §5.2. nodeId=null은 루트 — 관리자 전용. public은 read 전용이라 edit에 무관. */
    @Transactional(readOnly = true)
    public boolean canEdit(UserRow user, String nodeId) {
        if (user == null) return !serverMode;
        if (isAdmin(user)) return true;
        if (nodeId == null) return false;
        List<String> chain = acl.ancestorChain(nodeId);
        if (chain.isEmpty()) return false;  // 미존재 nodeId는 default-deny — IN-리스트 비지 않음 계약 enforce
        Access access = resolveAcl(user, chain);
        return access != Access.DENY && access.allowsEdit() && roleHas(user, "res.edit");
    }

    /**
     * GET /tree 필터 — 읽을 수 있는 노드 + 그 조상 폴더 스텁(이름만 노출 — 경로 없이 트리 UI 불성립).
     * 계약: user=null을 받지 않는다 — local 모드·관리자는 호출자(VaultGuard)가 무필터 처리.
     */
    @Transactional(readOnly = true)
    public Set<String> readableIds(UserRow user, List<NodeRow> activeNodes) {
        if (!roleHas(user, "res.read")) return Set.of();
        List<String> principals = principals(user);
        Map<String, Map<String, String>> aclByPrincipal = groupAcl(acl.findAllAcl());
        Map<String, String> flags = flagMap(acl.findAllPublicFlags());
        Map<String, List<NodeRow>> byParent = new LinkedHashMap<>();
        for (NodeRow row : activeNodes) {
            byParent.computeIfAbsent(row.parentId(), k -> new ArrayList<>()).add(row);
        }
        Set<String> out = new HashSet<>();
        Map<String, String> rootNearest = new HashMap<>();   // 주체별 nearest — 루트에선 전부 미설정
        for (NodeRow root : byParent.getOrDefault(null, List.of())) {
            walk(root, byParent, aclByPrincipal, flags, principals, rootNearest, null, out);
        }
        return out;
    }

    // ---- internal ----

    private List<String> principals(UserRow user) {
        List<String> keys = new ArrayList<>();
        keys.add("user:" + user.id());
        for (String teamId : teams.teamsOf(user.id())) {
            keys.add("team:" + teamId);
        }
        keys.add("all:@all");
        return keys;
    }

    private static String key(AclRow row) {
        return row.principalType() + ":" + row.principalId();
    }

    private static Map<String, Map<String, String>> groupAcl(List<AclRow> rows) {
        Map<String, Map<String, String>> out = new HashMap<>();
        for (AclRow row : rows) {
            out.computeIfAbsent(key(row), k -> new HashMap<>()).put(row.nodeId(), row.grantType());
        }
        return out;
    }

    private static Map<String, String> flagMap(List<PublicFlagRow> rows) {
        Map<String, String> out = new HashMap<>();
        for (PublicFlagRow row : rows) {
            out.put(row.nodeId(), row.mode());
        }
        return out;
    }

    private Access resolveAcl(UserRow user, List<String> chain) {
        Map<String, Map<String, String>> byPrincipal = groupAcl(acl.findAclForNodes(chain));
        List<String> nearest = new ArrayList<>();
        for (String principal : principals(user)) {
            nearest.add(AclResolver.nearestExplicit(chain, byPrincipal.getOrDefault(principal, Map.of())));
        }
        return AclResolver.combine(nearest);
    }

    /**
     * 트리 top-down walk — 주체별 nearest grant와 public 상태를 부모에서 물려받아 갱신.
     * deny-sticky: 조상에서 deny된 주체는 더 깊은 allow로 재허용되지 않음 (스펙 §5.1).
     * activeNodes에 부모가 없는 고아 노드는 미방문(=제외, fail-closed) —
     * soft-delete가 서브트리 단위라 정상 데이터에선 발생 안 함.
     * @return 이 노드(또는 자손)가 포함됐는지 — 폴더 스텁 판단용
     */
    private boolean walk(NodeRow node, Map<String, List<NodeRow>> byParent,
                         Map<String, Map<String, String>> aclByPrincipal, Map<String, String> flags,
                         List<String> principals, Map<String, String> inheritedNearest,
                         Boolean inheritedPublic, Set<String> out) {
        Map<String, String> nearest = inheritedNearest;
        for (String principal : principals) {
            String grant = aclByPrincipal.getOrDefault(principal, Map.of()).get(node.id());
            // deny-sticky: 조상에서 deny된 주체는 더 깊은 allow로 재허용되지 않음 (스펙 §5.1)
            if (grant != null && !"deny".equals(nearest.get(principal))) {
                if (nearest == inheritedNearest) nearest = new HashMap<>(inheritedNearest);  // copy-on-write
                nearest.put(principal, grant);
            }
        }
        Boolean publicState = inheritedPublic;
        String flag = flags.get(node.id());
        if (flag != null) {
            publicState = "public".equals(flag);
        }
        Access access = AclResolver.combine(nearest.values());
        boolean selfReadable = access != Access.DENY
            && (access.allowsRead() || Boolean.TRUE.equals(publicState));
        boolean anyChild = false;
        for (NodeRow child : byParent.getOrDefault(node.id(), List.of())) {
            anyChild |= walk(child, byParent, aclByPrincipal, flags, principals, nearest, publicState, out);
        }
        // 폴더 스텁: 자손이 읽히면 경로(이름만) 노출 — 폴더 한정 (데이터 손상으로 노트가 자식을 가져도 노트 content가 스텁으로 노출되지 않게)
        boolean include = selfReadable || (anyChild && "folder".equals(node.type()));
        if (include) {
            out.add(node.id());
        }
        return include;
    }
}
