package com.worknote.admin;

import com.worknote.acl.PermissionService;
import com.worknote.auth.CredentialRow;
import com.worknote.auth.PasswordHasher;
import com.worknote.auth.RoleMapper;
import com.worknote.auth.UserMapper;
import com.worknote.auth.UserRow;
import com.worknote.vault.VaultException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 사용자 관리 — 락아웃 방지(자기 자신 권한 변경 금지 + 마지막 활성 관리자 보호)가 핵심 정책. */
@Service
public class UserAdminService {

    private static final String ACTIVE = "active";

    private final UserMapper users;
    private final RoleMapper roles;
    private final PermissionService perm;

    public UserAdminService(UserMapper users, RoleMapper roles, PermissionService perm) {
        this.users = users;
        this.roles = roles;
        this.perm = perm;
    }

    public List<UserRow> list() {
        return users.findAll();
    }

    @Transactional
    public UserRow create(String emp, String name, String email, String roleId, String password) {
        if (users.findByEmp(emp) != null) {
            throw VaultException.conflict("이미 사용 중인 사번입니다: " + emp);
        }
        requireRole(roleId);
        String id = "u-" + UUID.randomUUID();
        String salt = PasswordHasher.newSalt();
        UserRow user = new UserRow(id, emp, email, name, roleId, ACTIVE, null);
        try {
            users.insert(user);
        } catch (DuplicateKeyException e) {
            // findByEmp 선검사와 insert 사이 race — pool=1로 사실상 도달 불가지만 도달 시 500 대신 409 계약 유지
            throw VaultException.conflict("이미 사용 중인 사번입니다: " + emp);
        }
        users.insertCredential(new CredentialRow(id, salt, PasswordHasher.hash(password, salt)));
        return user;
    }

    @Transactional
    public UserRow update(UserRow actor, String id, String name, String email, String roleId, String status) {
        UserRow target = require(id);
        if (actor != null && actor.id().equals(id) && (roleId != null || status != null)) {
            throw VaultException.invalid("자기 자신의 역할·상태는 변경할 수 없습니다");
        }
        if (roleId != null) {
            requireRole(roleId);
        }
        UserRow merged = new UserRow(target.id(), target.emp(),
            email != null ? email : target.email(),
            name != null ? name : target.name(),
            roleId != null ? roleId : target.roleId(),
            status != null ? status : target.status(),
            target.lastLogin());
        requireNotLastAdminDowngrade(target, merged);
        users.update(merged);
        return merged;
    }

    @Transactional
    public UserRow approve(String id) {
        UserRow target = require(id);
        if (!"pending".equals(target.status())) {
            throw VaultException.conflict("승인 대기 상태가 아닙니다: " + target.status());
        }
        UserRow merged = new UserRow(target.id(), target.emp(), target.email(), target.name(),
            target.roleId(), ACTIVE, target.lastLogin());
        users.update(merged);
        return merged;
    }

    @Transactional
    public UserRow resetPassword(String id, String password) {
        UserRow target = require(id);
        String salt = PasswordHasher.newSalt();
        CredentialRow cred = new CredentialRow(id, salt, PasswordHasher.hash(password, salt));
        if (users.updateCredential(cred) == 0) {
            users.insertCredential(cred); // 자격증명 누락(비정상 데이터) 복구
        }
        return target;
    }

    private UserRow require(String id) {
        UserRow row = users.findById(id);
        if (row == null) {
            throw VaultException.notFound("사용자가 없습니다: " + id);
        }
        return row;
    }

    private void requireRole(String roleId) {
        if (roles.findById(roleId) == null) {
            throw VaultException.invalid("존재하지 않는 역할: " + roleId);
        }
    }

    /** 활성 관리자였던 대상이 비관리자/비활성이 되면 남는 활성 관리자가 있어야 한다 — 폐쇄망 락아웃 방지. */
    private void requireNotLastAdminDowngrade(UserRow before, UserRow after) {
        boolean wasActiveAdmin = ACTIVE.equals(before.status()) && perm.isAdmin(before);
        boolean staysActiveAdmin = ACTIVE.equals(after.status()) && perm.isAdmin(after);
        if (!wasActiveAdmin || staysActiveAdmin) {
            return;
        }
        boolean anotherActiveAdmin = users.findAll().stream()
            .anyMatch(u -> !u.id().equals(before.id()) && ACTIVE.equals(u.status()) && perm.isAdmin(u));
        if (!anotherActiveAdmin) {
            throw VaultException.invalid("마지막 활성 관리자는 강등·비활성화할 수 없습니다");
        }
    }
}
