package com.worknote.acl;

import com.worknote.auth.UserMapper;
import com.worknote.auth.UserRow;
import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이동 노출 델타 — 스펙 §4.3 move 행 / §7.
 * 노드 id는 mv- 접두로 다른 테스트와 격리(공유 in-memory DB지만 @BeforeEach 전삭제).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared")
class ExposureServiceTest {
    @Autowired ExposureService exposure;
    @Autowired AclMapper acl;
    @Autowired SpaceMapper space;
    @Autowired TeamMapper teams;
    @Autowired UserMapper users;
    @Autowired NodeMapper nodes;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM acl");
        jdbc.update("DELETE FROM public_flag");
        jdbc.update("DELETE FROM space");
        jdbc.update("DELETE FROM team_member");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM node");
    }

    private void node(String id, String parentId, String type) {
        nodes.insert(new NodeRow(id, parentId, type, "N-" + id, 1, null, null, null, null));
    }

    @Test
    void privateNoteMovedIntoPublicFolderTurnsPublic() {
        // mv-pub(public) , mv-priv(비공개) , mv-note 는 mv-priv 아래
        node("mv-pub", null, "folder");
        node("mv-priv", null, "folder");
        node("mv-note", "mv-priv", "note");
        acl.upsertPublicFlag("mv-pub", "public");

        MovePreview p = exposure.preview("mv-note", "mv-pub");

        assertThat(p.publicBefore()).isFalse();
        assertThat(p.publicAfter()).isTrue();
    }

    @Test
    void noteMovedOutOfPublicFolderTurnsPrivate() {
        node("mv-pub", null, "folder");
        node("mv-priv", null, "folder");
        node("mv-note", "mv-pub", "note");
        acl.upsertPublicFlag("mv-pub", "public");

        MovePreview p = exposure.preview("mv-note", "mv-priv");

        assertThat(p.publicBefore()).isTrue();
        assertThat(p.publicAfter()).isFalse();
    }

    @Test
    void targetFolderTeamGrantAppearsInAdded() {
        node("mv-src", null, "folder");
        node("mv-dst", null, "folder");
        node("mv-note", "mv-src", "note");
        teams.insertTeam("t-pay", "결제팀");
        acl.insertAcl(new AclRow("team", "t-pay", "mv-dst", "edit"));

        MovePreview p = exposure.preview("mv-note", "mv-dst");

        assertThat(p.added()).containsExactly("결제팀");
        assertThat(p.removed()).isEmpty();
    }

    @Test
    void sourceFolderTeamGrantAppearsInRemoved() {
        node("mv-src", null, "folder");
        node("mv-dst", null, "folder");
        node("mv-note", "mv-src", "note");
        teams.insertTeam("t-pay", "결제팀");
        acl.insertAcl(new AclRow("team", "t-pay", "mv-src", "edit"));

        MovePreview p = exposure.preview("mv-note", "mv-dst");

        assertThat(p.added()).isEmpty();
        assertThat(p.removed()).containsExactly("결제팀");
    }

    @Test
    void crossSpaceMoveReportsSpaceNamesAndFlag() {
        // mv-a(팀a 소유 최상위) , mv-b(팀b 소유 최상위)
        node("mv-a", null, "folder");
        node("mv-b", null, "folder");
        node("mv-note", "mv-a", "note");
        teams.insertTeam("t-a", "A팀");
        teams.insertTeam("t-b", "B팀");
        space.upsert("mv-a", "t-a");
        space.upsert("mv-b", "t-b");

        MovePreview p = exposure.preview("mv-note", "mv-b");

        assertThat(p.crossSpace()).isTrue();
        assertThat(p.fromSpace()).isEqualTo("A팀");
        assertThat(p.toSpace()).isEqualTo("B팀");
    }

    @Test
    void moveWithinSameTopLevelIsNotCrossSpace() {
        // 같은 최상위 mv-a 내부: 형제 폴더 sub1 -> sub2 로 이동
        node("mv-a", null, "folder");
        node("mv-sub1", "mv-a", "folder");
        node("mv-sub2", "mv-a", "folder");
        node("mv-note", "mv-sub1", "note");
        teams.insertTeam("t-a", "A팀");
        space.upsert("mv-a", "t-a");

        MovePreview p = exposure.preview("mv-note", "mv-sub2");

        assertThat(p.crossSpace()).isFalse();
        assertThat(p.fromSpace()).isEqualTo("A팀");
        assertThat(p.toSpace()).isEqualTo("A팀");
    }

    @Test
    void noExposureChangeWhenSiblingFoldersShareGrantAndPublic() {
        // 부모 mv-root 에 팀 grant + public → 자식 폴더 두 개는 동일 상속 → delta 없음
        node("mv-root", null, "folder");
        node("mv-s1", "mv-root", "folder");
        node("mv-s2", "mv-root", "folder");
        node("mv-note", "mv-s1", "note");
        teams.insertTeam("t-pay", "결제팀");
        acl.insertAcl(new AclRow("team", "t-pay", "mv-root", "read"));
        acl.upsertPublicFlag("mv-root", "public");

        MovePreview p = exposure.preview("mv-note", "mv-s2");

        assertThat(p.added()).isEmpty();
        assertThat(p.removed()).isEmpty();
        assertThat(p.publicBefore()).isEqualTo(p.publicAfter());
        assertThat(p.publicBefore()).isTrue();
        assertThat(p.crossSpace()).isFalse();
    }

    @Test
    void moveToRootUsesSelfChainAfter() {
        // mv-src 폴더에 팀 grant + public → 루트로 이동하면 전부 사라짐
        node("mv-src", null, "folder");
        node("mv-note", "mv-src", "note");
        teams.insertTeam("t-pay", "결제팀");
        acl.insertAcl(new AclRow("team", "t-pay", "mv-src", "read"));
        acl.upsertPublicFlag("mv-src", "public");

        MovePreview p = exposure.preview("mv-note", null);

        assertThat(p.publicBefore()).isTrue();
        assertThat(p.publicAfter()).isFalse();
        assertThat(p.removed()).containsExactly("결제팀");
        assertThat(p.added()).isEmpty();
    }

    @Test
    void labelsResolveUserEmpAndAtAllAndSortDeterministically() {
        // 대상에 user/team/@all grant 동시 → 라벨 해석 + 결정적 정렬(라벨 사전순) 확인
        node("mv-src", null, "folder");
        node("mv-dst", null, "folder");
        node("mv-note", "mv-src", "note");
        users.insert(new UserRow("u1", "10001", null, "홍길동", "operator", "active", null));
        teams.insertTeam("t-pay", "결제팀");
        acl.insertAcl(new AclRow("user", "u1", "mv-dst", "read"));
        acl.insertAcl(new AclRow("team", "t-pay", "mv-dst", "read"));
        acl.insertAcl(new AclRow("all", "@all", "mv-dst", "read"));

        MovePreview p = exposure.preview("mv-note", "mv-dst");

        // user 라벨은 emp(10001), @all 은 "전 직원", team 은 팀명 — 라벨 사전순 정렬
        assertThat(p.added()).containsExactly("10001", "결제팀", "전 직원");
        assertThat(p.removed()).isEmpty();
    }
}
