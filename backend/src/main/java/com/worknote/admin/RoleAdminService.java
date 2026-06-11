package com.worknote.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.worknote.acl.AclResolver;
import com.worknote.acl.PermissionService;
import com.worknote.auth.RoleCaps;
import com.worknote.auth.RoleMapper;
import com.worknote.auth.RoleRow;
import com.worknote.auth.UserMapper;
import com.worknote.vault.VaultException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 역할 관리. caps는 KNOWN_CAPS 화이트리스트 검증 — RoleCaps가 DB JSON을 신뢰하므로 쓰기 시점에 fail-fast. */
@Service
public class RoleAdminService {

    private static final String ACTIVE = "active";

    private static final Set<String> RES_CAPS = Set.of(
        "res.read", "res.edit", "res.create", "res.delete", "res.export", "res.share");
    static final Set<String> KNOWN_CAPS;
    static {
        Set<String> all = new HashSet<>(AclResolver.ADMIN_CAPS);
        all.addAll(RES_CAPS);
        KNOWN_CAPS = Set.copyOf(all);
    }

    public record RoleView(String id, String name, boolean system, Set<String> caps, int userCount) {}

    private final RoleMapper roles;
    private final RoleCaps roleCaps;
    private final UserMapper users;
    private final PermissionService perm;
    private final ObjectMapper json;

    public RoleAdminService(RoleMapper roles, RoleCaps roleCaps, UserMapper users,
                            PermissionService perm, ObjectMapper json) {
        this.roles = roles;
        this.roleCaps = roleCaps;
        this.users = users;
        this.perm = perm;
        this.json = json;
    }

    public List<RoleView> list() {
        return roles.findAll().stream().map(this::toView).toList();
    }

    @Transactional
    public RoleView create(String id, String name, List<String> caps) {
        if (roles.findById(id) != null) {
            throw VaultException.conflict("이미 존재하는 역할: " + id);
        }
        roles.insert(new RoleRow(id, name, 0, toJson(validated(caps))));
        return toView(roles.findById(id));
    }

    @Transactional
    public RoleView update(String id, String name, List<String> caps) {
        RoleRow row = require(id);
        if (row.system() == 1) {
            throw VaultException.invalid("시스템 역할은 수정할 수 없습니다: " + id);
        }
        String mergedName = name != null ? name : row.name();
        String mergedCaps = row.caps();
        if (caps != null) {
            Set<String> next = validated(caps);
            requireNotLastAdminRoleDowngrade(id, next);
            mergedCaps = toJson(next);
        }
        roles.update(new RoleRow(id, mergedName, 0, mergedCaps));
        return toView(roles.findById(id));
    }

    @Transactional
    public void delete(String id) {
        RoleRow row = require(id);
        if (row.system() == 1) {
            throw VaultException.invalid("시스템 역할은 삭제할 수 없습니다: " + id);
        }
        if (roles.countUsers(id) > 0) {
            throw VaultException.conflict("해당 역할을 사용하는 사용자가 있습니다: " + id);
        }
        roles.delete(id);
    }

    private RoleRow require(String id) {
        RoleRow row = roles.findById(id);
        if (row == null) {
            throw VaultException.notFound("역할이 없습니다: " + id);
        }
        return row;
    }

    private Set<String> validated(List<String> caps) {
        Set<String> set = new HashSet<>(caps);
        for (String cap : set) {
            if (!KNOWN_CAPS.contains(cap)) {
                throw VaultException.invalid("알 수 없는 권한: " + cap);
            }
        }
        return set;
    }

    /**
     * admin 역할(caps ⊇ ADMIN_CAPS)에서 admin caps를 잃는 수정은, 이 역할에 속하지 않은 다른 활성 관리자가
     * 있어야 허용 — 시스템 역할 보호를 우회하는 커스텀 admin 역할 강등 락아웃 경로 차단
     * (UserAdminService.requireNotLastAdminDowngrade와 같은 정책의 역할 축).
     */
    private void requireNotLastAdminRoleDowngrade(String roleId, Set<String> nextCaps) {
        boolean wasAdminRole = roleCaps.of(roleId).containsAll(AclResolver.ADMIN_CAPS);
        boolean staysAdminRole = nextCaps.containsAll(AclResolver.ADMIN_CAPS);
        if (!wasAdminRole || staysAdminRole) {
            return;
        }
        boolean anotherActiveAdmin = users.findAll().stream()
            .anyMatch(u -> ACTIVE.equals(u.status()) && !roleId.equals(u.roleId()) && perm.isAdmin(u));
        if (!anotherActiveAdmin) {
            throw VaultException.invalid("마지막 활성 관리자의 역할에서 관리자 권한을 제거할 수 없습니다");
        }
    }

    private String toJson(Set<String> caps) {
        try {
            return json.writeValueAsString(caps);
        } catch (Exception e) {
            throw new IllegalStateException("caps 직렬화 실패", e);
        }
    }

    private RoleView toView(RoleRow row) {
        return new RoleView(row.id(), row.name(), row.system() == 1,
            roleCaps.of(row.id()), roles.countUsers(row.id()));
    }
}
