package com.worknote.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Set;

/** role.caps(JSON 배열 문자열) 파싱 — AuthService·PermissionService 공용. */
@Component
public class RoleCaps {

    private final RoleMapper roles;
    private final ObjectMapper json;

    public RoleCaps(RoleMapper roles, ObjectMapper json) {
        this.roles = roles;
        this.json = json;
    }

    public Set<String> of(String roleId) {
        RoleRow role = roles.findById(roleId);
        if (role == null) {
            return Set.of();   // 알 수 없는 역할 = 능력 없음 (default-deny)
        }
        try {
            return json.readValue(role.caps(), new TypeReference<Set<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("role.caps JSON 파싱 실패: " + roleId, e);
        }
    }
}
