package com.worknote.admin;

import com.worknote.acl.AclMapper;
import com.worknote.acl.AclRow;
import com.worknote.acl.SpaceMapper;
import com.worknote.acl.SpaceRow;
import com.worknote.acl.TeamMapper;
import com.worknote.admin.dto.AclEntryRequest;
import com.worknote.auth.UserMapper;
import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import com.worknote.vault.VaultException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ACL 관리 — 노드 단위 replace-all. 주체 존재 검증으로 유령 grant 방지.
 * PUT은 마지막 저장 승리(낙관적 잠금 없음) — 관리자 소수 + acl.set 감사로 재구성 가능 전제.
 */
@Service
public class AclAdminService {

    private final AclMapper acl;
    private final NodeMapper nodes;
    private final UserMapper users;
    private final TeamMapper teams;
    private final SpaceMapper spaces;

    public AclAdminService(AclMapper acl, NodeMapper nodes, UserMapper users, TeamMapper teams,
                           SpaceMapper spaces) {
        this.acl = acl;
        this.nodes = nodes;
        this.users = users;
        this.teams = teams;
        this.spaces = spaces;
    }

    public List<AclRow> listAll() {
        return acl.findAllAcl();
    }

    public List<AclRow> forNode(String nodeId) {
        requireActiveNode(nodeId);
        return acl.findAclForNodes(List.of(nodeId));
    }

    /**
     * replace-all. 반환값은 감사 target에 부기할 suffix(부기할 것 없으면 빈 문자열).
     * 스페이스 폴더인데 새 entries에 소유 팀 grant가 없어도 재주입하지 않는다 — replace-all 계약 유지.
     * 대신 부재 사실을 감사에 가시화한다(SpaceAdminService.set의 잔존 부기와 동일 패턴).
     */
    @Transactional
    public String replace(String nodeId, List<AclEntryRequest> entries) {
        requireActiveNode(nodeId);
        Set<String> seen = new HashSet<>();
        for (AclEntryRequest e : entries) {
            if (!seen.add(e.principalType() + ":" + e.principalId())) {
                throw VaultException.invalid("중복된 주체: " + e.principalType() + ":" + e.principalId());
            }
            validatePrincipal(e);
        }
        acl.deleteAclForNode(nodeId);
        for (AclEntryRequest e : entries) {
            acl.insertAcl(new AclRow(e.principalType(), e.principalId(), nodeId, e.grantType()));
        }
        SpaceRow space = spaces.find(nodeId);
        if (space != null && space.teamId() != null) {
            boolean ownerGranted = entries.stream()
                .anyMatch(e -> "team".equals(e.principalType()) && space.teamId().equals(e.principalId()));
            if (!ownerGranted) {
                return " (스페이스 소유 팀 " + space.teamId() + " grant 부재)";
            }
        }
        return "";
    }

    @Transactional
    public void setPublic(String nodeId, String mode) {
        requireActiveNode(nodeId);
        acl.upsertPublicFlag(nodeId, mode);
    }

    @Transactional
    public void unsetPublic(String nodeId) {
        requireActiveNode(nodeId);
        if (acl.deletePublicFlag(nodeId) == 0) {
            throw VaultException.notFound("public 설정이 없습니다: " + nodeId);
        }
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
