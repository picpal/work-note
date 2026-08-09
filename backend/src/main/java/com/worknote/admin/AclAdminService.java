package com.worknote.admin;

import com.worknote.acl.AclMapper;
import com.worknote.acl.AclRow;
import com.worknote.acl.PublicFlagRow;
import com.worknote.acl.SpaceMapper;
import com.worknote.acl.SpaceRow;
import com.worknote.acl.TeamMapper;
import com.worknote.admin.dto.AclEntryRequest;
import com.worknote.audit.AuditService;
import com.worknote.auth.UserMapper;
import com.worknote.auth.UserRow;
import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import com.worknote.vault.VaultException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ACL 관리 — 노드 단위 replace-all. 주체 존재 검증으로 유령 grant 방지.
 * PUT은 마지막 저장 승리(낙관적 잠금 없음) — 관리자 소수 + acl.set 감사로 재구성 가능 전제.
 *
 * <p>감사 기록이 서비스 안에 있는 이유(T7-a): 변경·델타·감사가 한 트랜잭션이어야 감사 행 누락이
 * 곧 변경 롤백이 된다. 컨트롤러에서 사후 기록하면 감사 insert만 실패했을 때 권한 변경이
 * 흔적 없이 남아 M-1(사후 재구성)의 목적이 무너진다.
 */
@Service
public class AclAdminService {

    private final AclMapper acl;
    private final NodeMapper nodes;
    private final UserMapper users;
    private final TeamMapper teams;
    private final SpaceMapper spaces;
    private final AuditService audit;
    private final AuditDelta delta;

    public AclAdminService(AclMapper acl, NodeMapper nodes, UserMapper users, TeamMapper teams,
                           SpaceMapper spaces, AuditService audit, AuditDelta delta) {
        this.acl = acl;
        this.nodes = nodes;
        this.users = users;
        this.teams = teams;
        this.spaces = spaces;
        this.audit = audit;
        this.delta = delta;
    }

    public List<AclRow> listAll() {
        return acl.findAllAcl();
    }

    public List<PublicFlagRow> listPublicFlags() {
        return acl.findAllPublicFlags();
    }

    public List<AclRow> forNode(String nodeId) {
        requireActiveNode(nodeId);
        return acl.findAclForNodes(List.of(nodeId));
    }

    /**
     * replace-all + acl.set 감사(델타 포함)를 한 트랜잭션으로.
     * 스페이스 폴더인데 새 entries에 소유 팀 grant가 없어도 재주입하지 않는다 — replace-all 계약 유지.
     * 대신 부재 사실을 감사 target에 가시화한다(SpaceAdminService.set의 잔존 부기와 동일 패턴).
     */
    @Transactional
    public void replace(String nodeId, List<AclEntryRequest> entries, UserRow actor, String ip) {
        requireActiveNode(nodeId);
        Set<String> seen = new HashSet<>();
        for (AclEntryRequest e : entries) {
            if (!seen.add(e.principalType() + ":" + e.principalId())) {
                throw VaultException.invalid("중복된 주체: " + e.principalType() + ":" + e.principalId());
            }
            validatePrincipal(e);
        }
        // 델타의 before는 반드시 선삭제 '직전'에 읽는다 — 삭제 후에는 무엇이 회수됐는지 복원할 수 없다.
        Map<String, String> before = grantMap(acl.findAclForNodes(List.of(nodeId)));
        acl.deleteAclForNode(nodeId);
        Map<String, String> after = new LinkedHashMap<>();
        for (AclEntryRequest e : entries) {
            acl.insertAcl(new AclRow(e.principalType(), e.principalId(), nodeId, e.grantType()));
            after.put(e.principalType() + ":" + e.principalId(), e.grantType());
        }
        audit.log(actor, "acl.set", nodeId + " (" + entries.size() + "건)" + ownerTeamNote(nodeId, entries), ip,
            delta.acl(before, after));
    }

    @Transactional
    public void setPublic(String nodeId, String mode, UserRow actor, String ip) {
        requireActiveNode(nodeId);
        String before = publicModeOf(nodeId);
        acl.upsertPublicFlag(nodeId, mode);
        audit.log(actor, "public.set", nodeId + " " + mode, ip, delta.publicMode(before, mode));
    }

    @Transactional
    public void unsetPublic(String nodeId, UserRow actor, String ip) {
        requireActiveNode(nodeId);
        String before = publicModeOf(nodeId);
        if (acl.deletePublicFlag(nodeId) == 0) {
            throw VaultException.notFound("public 설정이 없습니다: " + nodeId);
        }
        audit.log(actor, "public.unset", nodeId, ip, delta.publicMode(before, null));
    }

    /** 감사 target 부기 — 스페이스 소유 팀 grant가 빠졌으면 그 사실을 남긴다. 없으면 빈 문자열. */
    private String ownerTeamNote(String nodeId, List<AclEntryRequest> entries) {
        SpaceRow space = spaces.find(nodeId);
        if (space == null || space.teamId() == null) {
            return "";
        }
        boolean ownerGranted = entries.stream()
            .anyMatch(e -> "team".equals(e.principalType()) && space.teamId().equals(e.principalId()));
        return ownerGranted ? "" : " (스페이스 소유 팀 " + space.teamId() + " grant 부재)";
    }

    private static Map<String, String> grantMap(List<AclRow> rows) {
        Map<String, String> m = new LinkedHashMap<>();
        for (AclRow r : rows) {
            m.put(r.principalType() + ":" + r.principalId(), r.grantType());
        }
        return m;
    }

    private String publicModeOf(String nodeId) {
        return acl.findPublicFlagsForNodes(List.of(nodeId)).stream()
            .findFirst().map(PublicFlagRow::mode).orElse(null);
    }

    private void validatePrincipal(AclEntryRequest e) {
        switch (e.principalType()) {
            case "user" -> {
                if (users.findById(e.principalId()) == null) {
                    throw VaultException.invalid("존재하지 않는 사용자: " + e.principalId());
                }
            }
            case "team" -> {
                if (teams.findById(e.principalId()) == null) {
                    throw VaultException.invalid("존재하지 않는 팀: " + e.principalId());
                }
            }
            case "all" -> {
                if (!"@all".equals(e.principalId())) {
                    throw VaultException.invalid("all 주체의 id는 @all이어야 합니다");
                }
            }
            default -> throw VaultException.invalid("알 수 없는 주체 유형: " + e.principalType());
        }
    }

    private void requireActiveNode(String nodeId) {
        NodeRow node = nodes.findById(nodeId);
        if (node == null || node.deletedAt() != null) {
            throw VaultException.notFound("노드가 없습니다: " + nodeId);
        }
    }
}
