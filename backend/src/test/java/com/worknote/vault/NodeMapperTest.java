package com.worknote.vault;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared")
class NodeMapperTest {
    @Autowired NodeMapper mapper;
    @Autowired JdbcTemplate jdbc;

    NodeRow folder(String id, String parentId) { return new NodeRow(id, parentId, "folder", "F-" + id, 1, null, null, null, null); }
    NodeRow note(String id, String parentId)   { return new NodeRow(id, parentId, "note", "N-" + id, 1, "body", "2026-06-11", null, null); }

    @BeforeEach
    void clean() { jdbc.update("DELETE FROM tag"); jdbc.update("DELETE FROM node"); }

    @Test
    void insertAndFindById() {
        mapper.insert(folder("f1", null));
        assertThat(mapper.findById("f1")).isNotNull();
        assertThat(mapper.findById("f1").type()).isEqualTo("folder");
    }

    @Test
    void findActiveReturnsOnlyNonDeleted() {
        mapper.insert(folder("f1", null));
        mapper.insert(note("n1", "f1"));
        mapper.softDeleteSubtree("n1", "2026-06-11T10:00:00", "me");
        assertThat(mapper.findActive()).extracting(NodeRow::id).containsExactly("f1");
    }

    @Test
    void subtreeIdsCollectsDescendants() {
        mapper.insert(folder("f1", null));
        mapper.insert(folder("f2", "f1"));
        mapper.insert(note("n1", "f2"));
        assertThat(mapper.subtreeIds("f1")).containsExactlyInAnyOrder("f1", "f2", "n1");
    }

    @Test
    void softDeleteAndRestoreSubtree() {
        mapper.insert(folder("f1", null));
        mapper.insert(note("n1", "f1"));
        mapper.softDeleteSubtree("f1", "2026-06-11T10:00:00", "me");
        assertThat(mapper.findActive()).isEmpty();
        assertThat(mapper.findTrashRoots()).extracting(NodeRow::id).containsExactly("f1");
        mapper.restoreSubtree("f1");
        assertThat(mapper.findActive()).hasSize(2);
    }

    @Test
    void restoreKeepsIndependentlyDeletedChildInTrash() {
        mapper.insert(folder("f1", null));
        mapper.insert(note("n1", "f1"));
        mapper.softDeleteSubtree("n1", "2026-06-11T09:00:00", "me");   // n1 먼저 독립 삭제
        mapper.softDeleteSubtree("f1", "2026-06-11T10:00:00", "me");   // 조상 삭제
        mapper.restoreSubtree("f1");                                    // 조상만 복구
        assertThat(mapper.findActive()).extracting(NodeRow::id).containsExactly("f1");   // n1은 여전히 삭제
        assertThat(mapper.findTrashRoots()).extracting(NodeRow::id).containsExactly("n1"); // n1이 휴지통 루트로 재등장
    }

    @Test
    void purgeSubtreeDeletesRowsAndTags() {
        mapper.insert(folder("f1", null));
        mapper.insert(note("n1", "f1"));
        mapper.replaceTags("n1", java.util.List.of("a", "b"));
        mapper.softDeleteSubtree("f1", "2026-06-11T10:00:00", "me");
        mapper.deleteTagsIn(mapper.subtreeIds("f1"));   // 서비스가 수행할 시퀀스 그대로
        mapper.purgeSubtree("f1");
        assertThat(mapper.findById("f1")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag", Integer.class)).isZero();
    }

    @Test
    void updateFieldsAndMove() {
        mapper.insert(folder("f1", null));
        mapper.insert(folder("f2", null));
        mapper.insert(note("n1", "f1"));
        mapper.updateFields("n1", "renamed", "new body", "2026-06-11T11:00:00");
        assertThat(mapper.findById("n1").name()).isEqualTo("renamed");
        mapper.move("n1", "f2", 5);
        assertThat(mapper.findById("n1").parentId()).isEqualTo("f2");
    }

    @Test
    void maxPositionAmongSiblings() {
        mapper.insert(folder("f1", null));
        assertThat(mapper.maxPosition("f1")).isZero();   // 자식 없음 → 0
        mapper.insert(note("n1", "f1"));
        assertThat(mapper.maxPosition("f1")).isEqualTo(1);
    }

    @Test
    void tagsRoundTrip() {
        mapper.insert(note("n1", null));
        mapper.replaceTags("n1", java.util.List.of("운영", "flow"));
        assertThat(mapper.findTags("n1")).containsExactlyInAnyOrder("운영", "flow");
        mapper.replaceTags("n1", java.util.List.of());
        assertThat(mapper.findTags("n1")).isEmpty();
    }
}
