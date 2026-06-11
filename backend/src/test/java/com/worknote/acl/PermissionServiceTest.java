package com.worknote.acl;

import com.worknote.auth.UserRow;
import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared")
class PermissionServiceTest {
    @Autowired PermissionService perm;
    @Autowired AclMapper acl;
    @Autowired TeamMapper teams;
    @Autowired NodeMapper nodes;
    @Autowired JdbcTemplate jdbc;

    private static final UserRow OPERATOR = new UserRow("u1", "10001", null, "운영", "operator", "active", null);
    private static final UserRow VISITOR  = new UserRow("u2", "10002", null, "방문", "visitor", "active", null);
    private static final UserRow ADMIN    = new UserRow("u9", "90009", null, "관리", "admin", "active", null);

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM acl");
        jdbc.update("DELETE FROM public_flag");
        jdbc.update("DELETE FROM team_member");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM node");
        // 트리: f1 > f2 > n1,  f1 > n2
        node("f1", null, "folder");
        node("f2", "f1", "folder");
        node("n1", "f2", "note");
        node("n2", "f1", "note");
    }

    private void node(String id, String parentId, String type) {
        nodes.insert(new NodeRow(id, parentId, type, "N-" + id, 1, null, null, null, null));
    }

    @Test
    void defaultDenyWithoutAcl() {
        assertThat(perm.canRead(OPERATOR, "n1")).isFalse();
        assertThat(perm.canEdit(OPERATOR, "n1")).isFalse();
    }

    @Test
    void folderGrantInheritsToDescendants() {
        acl.insertAcl(new AclRow("user", "u1", "f1", "edit"));
        assertThat(perm.canRead(OPERATOR, "n1")).isTrue();
        assertThat(perm.canEdit(OPERATOR, "n1")).isTrue();
    }

    @Test
    void carveOutDenyOnNote() {
        acl.insertAcl(new AclRow("user", "u1", "f1", "edit"));
        acl.insertAcl(new AclRow("user", "u1", "n1", "deny"));
        assertThat(perm.canRead(OPERATOR, "n1")).isFalse();
        assertThat(perm.canRead(OPERATOR, "n2")).isTrue();   // 형제는 영향 없음
    }

    @Test
    void denyOnAncestorIsNotReopenedByDeeperAllow() {
        // deny-sticky: f1 deny + n1 read(같은 주체) → 차단 (§5.1 재허용 없음)
        acl.insertAcl(new AclRow("user", "u1", "f1", "deny"));
        acl.insertAcl(new AclRow("user", "u1", "n1", "read"));
        assertThat(perm.canRead(OPERATOR, "n1")).isFalse();
    }

    @Test
    void teamDenyBeatsPersonalGrant() {
        teams.insertTeam("t1", "결제팀");
        teams.addMember("t1", "u1");
        acl.insertAcl(new AclRow("user", "u1", "n1", "edit"));
        acl.insertAcl(new AclRow("team", "t1", "f1", "deny"));
        assertThat(perm.canRead(OPERATOR, "n1")).isFalse();  // deny-우선 합집합
    }

    @Test
    void allowUnionAcrossPrincipals() {
        teams.insertTeam("t1", "결제팀");
        teams.addMember("t1", "u1");
        acl.insertAcl(new AclRow("team", "t1", "f1", "read"));
        acl.insertAcl(new AclRow("user", "u1", "f2", "edit"));
        assertThat(perm.canEdit(OPERATOR, "n1")).isTrue();   // 합집합의 최대치
        assertThat(perm.canEdit(OPERATOR, "n2")).isFalse();  // f2 밖은 read뿐
        assertThat(perm.canRead(OPERATOR, "n2")).isTrue();
    }

    @Test
    void roleCapsCapAclGrant() {
        // 방문자(res.read만)에게 edit grant → read는 되지만 edit은 역할 상한에 캡
        acl.insertAcl(new AclRow("user", "u2", "f1", "edit"));
        assertThat(perm.canRead(VISITOR, "n1")).isTrue();
        assertThat(perm.canEdit(VISITOR, "n1")).isFalse();
    }

    @Test
    void publicFolderCascadeAndNoteExclude() {
        acl.insertPublicFlag("f1", "public");
        assertThat(perm.canRead(VISITOR, "n1")).isTrue();    // public cascade
        assertThat(perm.canEdit(VISITOR, "n1")).isFalse();   // public은 read 전용
        acl.insertPublicFlag("n1", "exclude");
        assertThat(perm.canRead(VISITOR, "n1")).isFalse();   // 노트 exclude 카브아웃
    }

    @Test
    void denyBeatsPublic() {
        acl.insertPublicFlag("f1", "public");
        acl.insertAcl(new AclRow("user", "u2", "n1", "deny"));
        assertThat(perm.canRead(VISITOR, "n1")).isFalse();
    }

    @Test
    void atAllGrantAppliesToEveryone() {
        acl.insertAcl(new AclRow("all", "@all", "f1", "read"));
        assertThat(perm.canRead(VISITOR, "n1")).isTrue();
        assertThat(perm.canRead(OPERATOR, "n2")).isTrue();
    }

    @Test
    void adminBypassesDeny() {
        acl.insertAcl(new AclRow("user", "u9", "f1", "deny"));
        assertThat(perm.canRead(ADMIN, "n1")).isTrue();
        assertThat(perm.canEdit(ADMIN, "n1")).isTrue();
        assertThat(perm.canEdit(ADMIN, null)).isTrue();      // 루트 edit = 관리자만
    }

    @Test
    void rootEditDeniedForNonAdmin() {
        assertThat(perm.canEdit(OPERATOR, null)).isFalse();
    }

    @Test
    void readableIdsFiltersTreeWithFolderStubs() {
        // 깊은 노트에만 grant → 조상 폴더는 스텁으로 포함
        acl.insertAcl(new AclRow("user", "u1", "n1", "read"));
        Set<String> ids = perm.readableIds(OPERATOR, nodes.findActive());
        assertThat(ids).containsExactlyInAnyOrder("n1", "f2", "f1");  // n2 제외
    }

    @Test
    void readableIdsRespectsDenyAndPublic() {
        acl.insertPublicFlag("f1", "public");
        acl.insertPublicFlag("n1", "exclude");
        acl.insertAcl(new AclRow("user", "u2", "n2", "deny"));
        Set<String> ids = perm.readableIds(VISITOR, nodes.findActive());
        assertThat(ids).containsExactlyInAnyOrder("f1", "f2");  // n1=exclude, n2=deny — f2는 public 폴더 자체
    }

    @Test
    void readableIdsDenyStickyInWalk() {
        // f1 deny(개인) + n1 read(개인) → walk에서도 재허용 없음 → 전부 미포함
        acl.insertAcl(new AclRow("user", "u1", "f1", "deny"));
        acl.insertAcl(new AclRow("user", "u1", "n1", "read"));
        assertThat(perm.readableIds(OPERATOR, nodes.findActive())).isEmpty();
    }

    @Test
    void readableIdsEmptyForNoGrants() {
        assertThat(perm.readableIds(OPERATOR, nodes.findActive())).isEmpty();
    }
}
