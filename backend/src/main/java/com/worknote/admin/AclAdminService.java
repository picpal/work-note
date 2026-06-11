package com.worknote.admin;

import com.worknote.acl.AclMapper;
import com.worknote.acl.AclRow;
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

/** ACL 관리 — 노드 단위 replace-all. 주체 존재 검증으로 유령 grant 방지. */
@Service
public class AclAdminService {

    private final AclMapper acl;
    private final NodeMapper nodes;
    private final UserMapper users;
    private final TeamMapper teams;

    public AclAdminService(AclMapper acl, NodeMapper nodes, UserMapper users, TeamMapper teams) {
        this.acl = acl;
        this.nodes = nodes;
        this.users = users;
        this.teams = teams;
    }

    public List<AclRow> listAll() {
        return acl.findAllAcl();
    }

    public List<AclRow> forNode(String nodeId) {
        requireActiveNode(nodeId);
        return acl.findAclForNodes(List.of(nodeId));
    }

    @Transactional
    public void replace(String nodeId, List<AclEntryRequest> entries) {
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
