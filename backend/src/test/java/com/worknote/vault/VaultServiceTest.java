package com.worknote.vault;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared")
class VaultServiceTest {
    @Autowired VaultService svc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM tag");
        jdbc.update("DELETE FROM acl");
        jdbc.update("DELETE FROM public_flag");
        jdbc.update("DELETE FROM space");
        jdbc.update("DELETE FROM node");
    }

    @Test
    void createAssignsPositionAndBuildsTree() {
        svc.create("f1", null, "folder", "아키텍처", null);
        svc.create("n1", "f1", "note", "결제 파이프라인", "body");
        svc.create("n2", "f1", "note", "승인 시퀀스", "body2");
        List<VaultNode> tree = svc.tree();
        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).children()).extracting(VaultNode::title)
            .containsExactly("결제 파이프라인", "승인 시퀀스");   // position 순
    }

    @Test
    void createUnderNoteRejected() {
        svc.create("n1", null, "note", "노트", "");
        assertThatThrownBy(() -> svc.create("n2", "n1", "note", "자식", ""))
            .isInstanceOf(VaultException.class).hasMessageContaining("폴더가 아닙니다");
    }

    @Test
    void createWithMissingParentRejected() {
        assertThatThrownBy(() -> svc.create("n1", "ghost", "note", "x", ""))
            .isInstanceOf(VaultException.class);
    }

    @Test
    void duplicateIdRejected() {
        svc.create("n1", null, "note", "a", "");
        assertThatThrownBy(() -> svc.create("n1", null, "note", "b", ""))
            .isInstanceOf(VaultException.class).hasMessageContaining("이미 존재");
    }

    @Test
    void moveIntoOwnDescendantRejected() {
        svc.create("f1", null, "folder", "A", null);
        svc.create("f2", "f1", "folder", "B", null);
        assertThatThrownBy(() -> svc.move("f1", "f2"))
            .isInstanceOf(VaultException.class).hasMessageContaining("하위로 이동");
    }

    @Test
    void moveToRootAndIntoFolder() {
        svc.create("f1", null, "folder", "A", null);
        svc.create("n1", "f1", "note", "x", "");
        svc.move("n1", null);                       // 루트로
        assertThat(svc.tree()).extracting(VaultNode::id).contains("n1");
        svc.move("n1", "f1");                       // 다시 폴더로
        assertThat(svc.tree().get(0).children()).extracting(VaultNode::id).contains("n1");
    }

    @Test
    void updateStampsUpdatedAtAndReplacesTags() {
        svc.create("n1", null, "note", "x", "");
        svc.update("n1", "새 제목", "새 본문", List.of("운영"), "local");
        VaultNode n = svc.tree().get(0);
        assertThat(n.title()).isEqualTo("새 제목");
        assertThat(n.tags()).containsExactly("운영");
        assertThat(n.updated()).matches("\\d{4}-\\d{2}-\\d{2}");
    }

    @Test
    void trashLifecycle() {
        svc.create("f1", null, "folder", "A", null);
        svc.create("n1", "f1", "note", "x", "");
        svc.trash("f1", "S2019-0007");
        assertThat(svc.tree()).isEmpty();
        assertThat(svc.trashList()).extracting(VaultNode::id).containsExactly("f1");
        svc.restore("f1");
        assertThat(svc.tree()).hasSize(1);
        svc.trash("f1", "S2019-0007");
        svc.purge("f1");
        assertThat(svc.trashList()).isEmpty();
        assertThat(svc.tree()).isEmpty();
    }

    @Test
    void purgeActiveNodeRejected() {
        svc.create("n1", null, "note", "x", "");
        assertThatThrownBy(() -> svc.purge("n1"))
            .isInstanceOf(VaultException.class);
    }

    @Test
    void purgeRemovesAclAndPublicFlagRows() {
        // create는 클라이언트 id를 받으므로, purge가 권한 행을 안 지우면 같은 id 재생성 시 옛 권한이 부활(fail-open)
        svc.create("f1", null, "folder", "팀폴더", null);
        svc.create("n1", "f1", "note", "노트", "");
        jdbc.update("INSERT INTO acl (principal_type, principal_id, node_id, grant_type) VALUES ('user','u1','f1','edit')");
        jdbc.update("INSERT INTO acl (principal_type, principal_id, node_id, grant_type) VALUES ('team','t1','n1','read')");
        jdbc.update("INSERT INTO public_flag (node_id, mode) VALUES ('f1','public')");
        jdbc.update("INSERT INTO space (node_id, team_id) VALUES ('f1', NULL)");

        svc.trash("f1", "S2019-0007");
        svc.purge("f1");   // 서브트리(f1, n1) 전체의 종속행이 함께 삭제되어야 함

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM acl WHERE node_id IN ('f1','n1')", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public_flag WHERE node_id IN ('f1','n1')", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM space WHERE node_id IN ('f1','n1')", Integer.class)).isZero();

        // 같은 id 재생성 — 잔여 권한 없음 (옛 ACL·public이 새 노드에 붙지 않는다)
        svc.create("f1", null, "folder", "재생성", null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM acl WHERE node_id = 'f1'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public_flag WHERE node_id = 'f1'", Integer.class)).isZero();
    }

    @Test
    void unknownIdThrowsNotFound() {
        assertThatThrownBy(() -> svc.trash("ghost", "me")).isInstanceOf(VaultException.class);
        assertThatThrownBy(() -> svc.move("ghost", null)).isInstanceOf(VaultException.class);
        assertThatThrownBy(() -> svc.update("ghost", "a", null, null, null)).isInstanceOf(VaultException.class);
    }
}
