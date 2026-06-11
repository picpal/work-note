package com.worknote.admin;

import com.worknote.acl.AclMapper;
import com.worknote.acl.TeamMapper;
import com.worknote.acl.TeamRow;
import com.worknote.auth.UserMapper;
import com.worknote.auth.UserRow;
import com.worknote.vault.VaultException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 팀 관리. 삭제 시 멤버십+팀 ACL 정리 — 잔여 행은 팀 id 재사용 시 권한 부활(purge 원칙과 동일). */
@Service
public class TeamAdminService {

    public record TeamView(String id, String name, List<UserRow> members) {}

    private final TeamMapper teams;
    private final UserMapper users;
    private final AclMapper acl;

    public TeamAdminService(TeamMapper teams, UserMapper users, AclMapper acl) {
        this.teams = teams;
        this.users = users;
        this.acl = acl;
    }

    public List<TeamView> list() {
        return teams.findAll().stream()
            .map(t -> new TeamView(t.id(), t.name(), teams.membersOf(t.id())))
            .toList();
    }

    @Transactional
    public TeamRow create(String name) {
        String id = "t-" + UUID.randomUUID();
        teams.insertTeam(id, name);
        return new TeamRow(id, name);
    }

    @Transactional
    public void rename(String id, String name) {
        require(id);
        teams.updateTeam(id, name);
    }

    @Transactional
    public void delete(String id) {
        require(id);
        if (teams.countSpaces(id) > 0) {
            throw VaultException.conflict("팀이 소유한 스페이스가 있습니다 — 먼저 스페이스 소유를 해제하세요");
        }
        teams.deleteMembers(id);
        acl.deleteAclByPrincipal("team", id);
        teams.deleteTeam(id);
    }

    @Transactional
    public UserRow addMember(String teamId, String userId) {
        require(teamId);
        UserRow user = users.findById(userId);
        if (user == null) {
            throw VaultException.invalid("존재하지 않는 사용자: " + userId);
        }
        if (teams.isMember(teamId, userId) > 0) {
            throw VaultException.conflict("이미 팀 멤버입니다: " + user.emp());
        }
        try {
            teams.addMember(teamId, userId);
        } catch (DuplicateKeyException e) {
            // isMember 선검사와 insert 사이 race — pool=1로 사실상 도달 불가지만 도달 시 500 대신 409 계약 유지
            throw VaultException.conflict("이미 팀 멤버입니다: " + user.emp());
        }
        return user;
    }

    @Transactional
    public void removeMember(String teamId, String userId) {
        require(teamId);
        if (teams.removeMember(teamId, userId) == 0) {
            throw VaultException.notFound("팀 멤버가 아닙니다: " + userId);
        }
    }

    private TeamRow require(String id) {
        TeamRow row = teams.findById(id);
        if (row == null) {
            throw VaultException.notFound("팀이 없습니다: " + id);
        }
        return row;
    }
}
