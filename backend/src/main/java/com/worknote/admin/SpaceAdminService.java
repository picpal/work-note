package com.worknote.admin;

import com.worknote.acl.AclMapper;
import com.worknote.acl.AclRow;
import com.worknote.acl.SpaceMapper;
import com.worknote.acl.SpaceRow;
import com.worknote.acl.TeamMapper;
import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import com.worknote.vault.VaultException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 팀 스페이스 = 최상위 폴더 + 소유 팀 메타데이터(스펙 §4.2). 팀 지정 시 edit ACL 자동 grant. */
@Service
public class SpaceAdminService {

    private final SpaceMapper spaces;
    private final NodeMapper nodes;
    private final TeamMapper teams;
    private final AclMapper acl;

    public SpaceAdminService(SpaceMapper spaces, NodeMapper nodes, TeamMapper teams, AclMapper acl) {
        this.spaces = spaces;
        this.nodes = nodes;
        this.teams = teams;
        this.acl = acl;
    }

    public List<SpaceRow> list() {
        return spaces.findAll();
    }

    @Transactional
    public void set(String nodeId, String teamId) {
        NodeRow node = nodes.findById(nodeId);
        if (node == null || node.deletedAt() != null) {
            throw VaultException.notFound("노드가 없습니다: " + nodeId);
        }
        if (!"folder".equals(node.type()) || node.parentId() != null) {
            throw VaultException.invalid("스페이스는 최상위 폴더만 지정할 수 있습니다");
        }
        if (teamId != null && teams.findById(teamId) == null) {
            throw VaultException.invalid("존재하지 않는 팀: " + teamId);
        }
        spaces.upsert(nodeId, teamId);
        if (teamId != null) {
            // 스펙 §4.2: 소유 팀 edit 자동 grant — 단, 그 팀의 명시 grant가 이미 있으면 존중
            boolean granted = acl.findAclForNodes(List.of(nodeId)).stream()
                .anyMatch(r -> "team".equals(r.principalType()) && teamId.equals(r.principalId()));
            if (!granted) {
                acl.insertAcl(new AclRow("team", teamId, nodeId, "edit"));
            }
        }
    }

    @Transactional
    public void unset(String nodeId) {
        if (spaces.delete(nodeId) == 0) {
            throw VaultException.notFound("스페이스가 아닙니다: " + nodeId);
        }
    }
}
