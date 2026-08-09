package com.worknote.admin;

import com.worknote.acl.AclMapper;
import com.worknote.admin.dto.AclEntryRequest;
import com.worknote.audit.AuditService;
import com.worknote.auth.UserMapper;
import com.worknote.auth.UserRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

/**
 * T7-a — 권한 변경 3경로(ACL replace / 역할 수정 / public 설정·해제)는 변경과 감사 기록이 원자적이어야 한다.
 * 감사 행이 없을 수 있으면 "누가 무슨 권한을 줬다 되돌렸는지" 사후 재구성이 불가능해져 델타 자체가 무의미해진다.
 * 나머지 감사 호출부의 fail-open 정책(AuditService 클래스 주석)은 그대로 둔다 — 여기만 fail-closed.
 *
 * <p>서비스 빈을 직접 호출한다. 트랜잭션 경계는 서비스 프록시에 있으므로 컨트롤러를 거치지 않아야
 * 롤백 여부를 정확히 관찰할 수 있다(핸들러 없는 RuntimeException은 MockMvc가 되던지기도 한다).
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:auditatomicmem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
class PermissionAuditAtomicityTest {

    @Autowired AclAdminService aclSvc;
    @Autowired RoleAdminService roleSvc;
    @Autowired AclMapper acl;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @MockBean AuditService audit;   // 기본 no-op — 특정 act만 doThrow로 실패시킨다

    private static final UserRow ACTOR =
        new UserRow("u-admin", "admin", null, "관리자", "admin", "active", null);

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM acl");
        jdbc.update("DELETE FROM public_flag");
        jdbc.update("DELETE FROM node");
        jdbc.update("DELETE FROM app_user WHERE id <> 'u-admin'");
        jdbc.update("DELETE FROM role WHERE id = 'r-tmp'");
        jdbc.update("INSERT INTO node (id, type, name, position) VALUES ('f1','folder','F1',1)");
        users.insert(new UserRow("u1", "10001", null, "홍길동", "operator", "active", null));
    }

    @Test
    void aclReplace_rollsBackWhenAuditInsertFails() {
        aclSvc.replace("f1", List.of(new AclEntryRequest("user", "u1", "read")), ACTOR, "10.0.0.1");
        assertThat(acl.findAclForNodes(List.of("f1"))).hasSize(1);

        doThrow(new IllegalStateException("감사 기록 실패"))
            .when(audit).log(any(), eq("acl.set"), any(), any(), any());

        assertThatThrownBy(() -> aclSvc.replace(
            "f1", List.of(new AclEntryRequest("user", "u1", "deny")), ACTOR, "10.0.0.1"))
            .isInstanceOf(IllegalStateException.class);

        // 감사 실패 → 변경 전 grant가 그대로 남아야 한다(선삭제까지 롤백)
        assertThat(acl.findAclForNodes(List.of("f1")))
            .singleElement()
            .extracting("grantType").isEqualTo("read");
    }

    @Test
    void roleUpdate_rollsBackWhenAuditInsertFails() {
        roleSvc.create("r-tmp", "임시역할", List.of("res.read"));   // create는 T7-a 범위 밖(기존 시그니처 유지)

        doThrow(new IllegalStateException("감사 기록 실패"))
            .when(audit).log(any(), eq("role.update"), any(), any(), any());

        assertThatThrownBy(() -> roleSvc.update(
            "r-tmp", "바뀐이름", List.of("res.read", "res.edit"), ACTOR, "10.0.0.1"))
            .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject(
            "SELECT name FROM role WHERE id = 'r-tmp'", String.class)).isEqualTo("임시역할");
        assertThat(jdbc.queryForObject(
            "SELECT caps FROM role WHERE id = 'r-tmp'", String.class)).doesNotContain("res.edit");
    }

    @Test
    void publicSet_rollsBackWhenAuditInsertFails() {
        doThrow(new IllegalStateException("감사 기록 실패"))
            .when(audit).log(any(), eq("public.set"), any(), any(), any());

        assertThatThrownBy(() -> aclSvc.setPublic("f1", "public", ACTOR, "10.0.0.1"))
            .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM public_flag WHERE node_id = 'f1'", Integer.class)).isZero();
    }

    @Test
    void publicUnset_rollsBackWhenAuditInsertFails() {
        aclSvc.setPublic("f1", "public", ACTOR, "10.0.0.1");

        doThrow(new IllegalStateException("감사 기록 실패"))
            .when(audit).log(any(), eq("public.unset"), any(), any(), any());

        assertThatThrownBy(() -> aclSvc.unsetPublic("f1", ACTOR, "10.0.0.1"))
            .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject(
            "SELECT mode FROM public_flag WHERE node_id = 'f1'", String.class)).isEqualTo("public");
    }
}
