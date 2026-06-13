package com.worknote.acl;

import com.worknote.auth.UserMapper;
import com.worknote.auth.UserRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 노드 이동 시 "노출(접근 집합)" 변화를 계산 — 스펙 §4.3 move 행 / §7.
 * 이동 전/후의 (allow 주체 집합 + public 여부 + 소유 스페이스)를 비교해 델타를 만든다.
 * 순수 해석은 AclResolver 재사용(PermissionService와 동일 컨벤션). 권한 enforce는 호출자 책임.
 */
@Service
public class ExposureService {

    private final AclMapper acl;
    private final SpaceMapper space;
    private final TeamMapper teams;
    private final UserMapper users;

    public ExposureService(AclMapper acl, SpaceMapper space, TeamMapper teams, UserMapper users) {
        this.acl = acl;
        this.space = space;
        this.teams = teams;
        this.users = users;
    }

    /** 이동 미리보기 — nodeId를 newParentId 아래로 옮길 때의 노출 델타. newParentId=null이면 루트로. */
    @Transactional(readOnly = true)
    public MovePreview preview(String nodeId, String newParentId) {
        List<String> chainBefore = acl.ancestorChain(nodeId);
        List<String> chainAfter = chainAfter(nodeId, newParentId);

        Snapshot before = snapshot(chainBefore);
        Snapshot after = snapshot(chainAfter);

        List<String> added = labels(difference(after.allow(), before.allow()));
        List<String> removed = labels(difference(before.allow(), after.allow()));

        String fromTeamId = teamIdOf(topLevel(nodeId, chainBefore));
        String toTeamId = teamIdOf(topLevel(nodeId, chainAfter));
        boolean crossSpace = !Objects.equals(fromTeamId, toTeamId);

        return new MovePreview(before.publicRead(), after.publicRead(),
            crossSpace, teamName(fromTeamId), teamName(toTeamId), added, removed);
    }

    // ---- chain ----

    /** 이동 후 체인: 루트면 [nodeId]만, 아니면 nodeId + newParent의 조상 체인(부모 포함). */
    private List<String> chainAfter(String nodeId, String newParentId) {
        Set<String> chain = new LinkedHashSet<>();
        chain.add(nodeId);
        if (newParentId != null) {
            chain.addAll(acl.ancestorChain(newParentId));
        }
        return new ArrayList<>(chain);
    }

    // ---- snapshot (allow 주체 + public) ----

    private record Snapshot(Set<String> allow, boolean publicRead) {}

    private Snapshot snapshot(List<String> chain) {
        if (chain.isEmpty()) {
            return new Snapshot(Set.of(), false);
        }
        Map<String, Map<String, String>> byPrincipal = groupAcl(acl.findAclForNodes(chain));
        Set<String> allow = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, String>> e : byPrincipal.entrySet()) {
            String g = AclResolver.nearestExplicit(chain, e.getValue());
            if ("read".equals(g) || "edit".equals(g)) {   // deny·null 제외
                allow.add(e.getKey());
            }
        }
        boolean pub = AclResolver.publicRead(chain, flagMap(acl.findPublicFlagsForNodes(chain)));
        return new Snapshot(allow, pub);
    }

    // ---- space ----

    /** 최상위 노드 = 체인의 마지막(루트). 체인 비면 자신을 최상위로 취급. */
    private String topLevel(String nodeId, List<String> chain) {
        return chain.isEmpty() ? nodeId : chain.get(chain.size() - 1);
    }

    private String teamIdOf(String topLevelNodeId) {
        if (topLevelNodeId == null) return null;
        SpaceRow row = space.find(topLevelNodeId);
        return row == null ? null : row.teamId();
    }

    private String teamName(String teamId) {
        if (teamId == null) return null;
        TeamRow row = teams.findById(teamId);
        return row == null ? null : row.name();
    }

    // ---- 라벨 해석 (M11) + 결정적 정렬(라벨 사전순) ----

    private static Set<String> difference(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.removeAll(b);
        return out;
    }

    /** principal key(type:id)를 사람이 읽는 라벨로 — all:@all=전 직원, team=팀명, user=emp. 라벨 사전순 정렬. */
    private List<String> labels(Set<String> principals) {
        Set<String> sorted = new TreeSet<>();   // 결정적 정렬(라벨 사전순)
        for (String key : principals) {
            sorted.add(label(key));
        }
        return new ArrayList<>(sorted);
    }

    private String label(String key) {
        int sep = key.indexOf(':');
        String type = key.substring(0, sep);
        String id = key.substring(sep + 1);
        return switch (type) {
            case "all" -> "전 직원";
            case "team" -> {
                TeamRow row = teams.findById(id);
                yield row == null ? id : row.name();
            }
            case "user" -> {
                UserRow row = users.findById(id);   // 소수라 건별 조회 허용
                yield row == null ? id : row.emp();
            }
            default -> id;
        };
    }

    // ---- 순수 헬퍼 (PermissionService private와 동일 — 공통화 금지 관례, 복붙 허용) ----

    private static Map<String, Map<String, String>> groupAcl(List<AclRow> rows) {
        Map<String, Map<String, String>> out = new HashMap<>();
        for (AclRow row : rows) {
            out.computeIfAbsent(row.principalType() + ":" + row.principalId(), k -> new HashMap<>())
                .put(row.nodeId(), row.grantType());
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
}
