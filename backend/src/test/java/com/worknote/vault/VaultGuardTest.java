package com.worknote.vault;

import com.worknote.acl.AclMapper;
import com.worknote.acl.AclRow;
import com.worknote.auth.UserRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:phase2mem?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=boot-pass-1"
})
class VaultGuardTest {
    @Autowired VaultGuard guard;
    @Autowired VaultService svc;
    @Autowired NodeMapper nodes;
    @Autowired AclMapper acl;
    @Autowired JdbcTemplate jdbc;

    private static final UserRow OPERATOR = new UserRow("u1", "10001", null, "운영", "operator", "active", null);
    private static final UserRow ADMIN    = new UserRow("u9", "90009", null, "관리", "admin", "active", null);

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM acl");
        jdbc.update("DELETE FROM public_flag");
        jdbc.update("DELETE FROM team_member");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM tag");
        jdbc.update("DELETE FROM node");
        nodes.insert(new NodeRow("f1", null, "folder", "F1", 1, null, null, null, null));
        nodes.insert(new NodeRow("n1", "f1", "note", "N1", 1, "body", "2026-06-11T09:00:00", null, null));
    }

    @Test
    void requireEditThrowsForbiddenWithoutGrant() {
        assertThatThrownBy(() -> guard.requireEdit(OPERATOR, "n1"))
            .isInstanceOf(VaultException.class)
            .satisfies(e -> assertThat(((VaultException) e).status()).isEqualTo(VaultException.Status.FORBIDDEN));
        acl.insertAcl(new AclRow("user", "u1", "f1", "edit"));
        assertThatCode(() -> guard.requireEdit(OPERATOR, "n1")).doesNotThrowAnyException();
    }

    @Test
    void requireCreateNeedsCapAndParentEdit() {
        acl.insertAcl(new AclRow("user", "u1", "f1", "edit"));
        assertThatCode(() -> guard.requireCreate(OPERATOR, "f1")).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.requireCreate(OPERATOR, null)).isInstanceOf(VaultException.class);  // 루트=관리자만
        assertThatCode(() -> guard.requireCreate(ADMIN, null)).doesNotThrowAnyException();
    }

    @Test
    void requireMoveNeedsBothEnds() {
        nodes.insert(new NodeRow("f2", null, "folder", "F2", 2, null, null, null, null));
        acl.insertAcl(new AclRow("user", "u1", "f1", "edit"));
        assertThatThrownBy(() -> guard.requireMove(OPERATOR, "n1", "f2")).isInstanceOf(VaultException.class);
        acl.insertAcl(new AclRow("user", "u1", "f2", "edit"));
        assertThatCode(() -> guard.requireMove(OPERATOR, "n1", "f2")).doesNotThrowAnyException();
    }

    @Test
    void restoreOnlyByDeleterOrAdmin() {
        svc.trash("n1", "10001");
        assertThatCode(() -> guard.requireRestore(OPERATOR, "n1")).doesNotThrowAnyException();
        UserRow other = new UserRow("u2", "10002", null, "남", "operator", "active", null);
        assertThatThrownBy(() -> guard.requireRestore(other, "n1")).isInstanceOf(VaultException.class);
        assertThatCode(() -> guard.requireRestore(ADMIN, "n1")).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.requireRestore(OPERATOR, "ghost")).isInstanceOf(VaultException.class);  // 미존재 id도 403 — 존재 비노출
    }

    @Test
    void requireDeleteNeedsCapAndEdit() {
        assertThatThrownBy(() -> guard.requireDelete(OPERATOR, "n1")).isInstanceOf(VaultException.class);
        acl.insertAcl(new AclRow("user", "u1", "f1", "edit"));
        assertThatCode(() -> guard.requireDelete(OPERATOR, "n1")).doesNotThrowAnyException();
        // res.delete cap 없는 visitor — edit grant가 있어도 역할 상한 캡으로 403
        UserRow visitor = new UserRow("u3", "10003", null, "방문", "visitor", "active", null);
        acl.insertAcl(new AclRow("user", "u3", "f1", "edit"));
        assertThatThrownBy(() -> guard.requireDelete(visitor, "n1")).isInstanceOf(VaultException.class);
    }

    @Test
    void restoreRequiresTrashRoot() {
        svc.trash("f1", "10001");   // f1>n1 통삭제
        assertThatThrownBy(() -> svc.restore("n1"))
            .isInstanceOf(VaultException.class)
            .satisfies(e -> assertThat(((VaultException) e).status()).isEqualTo(VaultException.Status.INVALID));
        assertThatCode(() -> svc.restore("f1")).doesNotThrowAnyException();
    }

    @Test
    void purgeAdminOnly() {
        assertThatThrownBy(() -> guard.requirePurge(OPERATOR)).isInstanceOf(VaultException.class);
        assertThatCode(() -> guard.requirePurge(ADMIN)).doesNotThrowAnyException();
    }

    @Test
    void treeFilterAndTrashFilter() {
        acl.insertAcl(new AclRow("user", "u1", "n1", "read"));
        Set<String> ids = guard.readableIds(OPERATOR);
        assertThat(ids).containsExactlyInAnyOrder("n1", "f1");
        assertThat(guard.readableIds(ADMIN)).isNull();        // null = 무필터
        assertThat(guard.trashFilter(OPERATOR)).isEqualTo("10001");
        assertThat(guard.trashFilter(ADMIN)).isNull();
    }

    @Test
    void vaultServiceTreeAcceptsFilter() {
        assertThat(svc.tree(Set.of("f1"))).hasSize(1);
        assertThat(svc.tree(Set.of("f1")).get(0).children()).isEmpty();   // n1 필터됨
        assertThat(svc.tree(null)).hasSize(1);                            // 무필터
        assertThat(svc.tree(null).get(0).children()).hasSize(1);
    }

    @Test
    void trashListFiltersByDeleter() {
        svc.trash("n1", "10001");
        assertThat(svc.trashList("10001")).hasSize(1);
        assertThat(svc.trashList("10002")).isEmpty();
        assertThat(svc.trashList(null)).hasSize(1);
    }
}
