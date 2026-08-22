package com.worknote.template;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** note_template 매퍼 — 가시성 필터(시스템+본인)와 정렬(시스템 우선·이름순)이 SQL 계약. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file:tplmem?mode=memory&cache=shared")
class NoteTemplateMapperTest {

    @Autowired NoteTemplateMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM note_template WHERE owner_id IS NOT NULL");
    }

    private NoteTemplateRow row(String id, String ownerId, String name) {
        return new NoteTemplateRow(id, ownerId, name, "## " + name + "\n- \n",
            "2026-08-18T10:00:00", "2026-08-18T10:00:00");
    }

    @Test
    void insertAndFind_roundTripsBodyWithNewlines() {
        mapper.insert(row("t1", "u1", "내 양식"));
        NoteTemplateRow found = mapper.find("t1");
        assertThat(found.ownerId()).isEqualTo("u1");
        assertThat(found.name()).isEqualTo("내 양식");
        assertThat(found.body()).isEqualTo("## 내 양식\n- \n");
    }

    @Test
    void listVisible_returnsSystemPlusOwnOnly() {
        mapper.insert(row("t1", "u1", "내 양식"));
        mapper.insert(row("t2", "u2", "남의 양식"));

        var ids = mapper.listVisible("u1").stream().map(NoteTemplateRow::id).toList();
        assertThat(ids).contains("t1", "tpl-meeting");   // 시드 시스템 템플릿은 항상 보임
        assertThat(ids).doesNotContain("t2");
    }

    @Test
    void listVisible_ordersSystemFirstThenByName() {
        mapper.insert(row("t1", "u1", "하하"));
        mapper.insert(row("t2", "u1", "가가"));

        var rows = mapper.listVisible("u1");
        // 앞쪽은 전부 시스템(owner_id IS NULL), 뒤쪽은 전부 개인
        int firstMine = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).ownerId() != null) { firstMine = i; break; }
        }
        assertThat(firstMine).isGreaterThan(0);
        assertThat(rows.subList(0, firstMine)).allMatch(r -> r.ownerId() == null);
        assertThat(rows.subList(firstMine, rows.size())).allMatch(r -> r.ownerId() != null);
        // 개인 그룹 내부는 이름 오름차순
        assertThat(rows.get(firstMine).name()).isEqualTo("가가");
    }

    @Test
    void listSystem_returnsOnlySeeds() {
        mapper.insert(row("t1", "u1", "내 양식"));
        assertThat(mapper.listSystem()).allMatch(r -> r.ownerId() == null);
        assertThat(mapper.listSystem()).extracting(NoteTemplateRow::name)
            .contains("회의록", "주간보고", "장애보고");
    }

    @Test
    void update_changesNameAndBodyKeepsCreatedAt() {
        mapper.insert(row("t1", "u1", "이전"));
        mapper.update(new NoteTemplateRow("t1", "u1", "이후", "새 본문",
            "2026-08-18T10:00:00", "2026-08-18T11:00:00"));

        NoteTemplateRow found = mapper.find("t1");
        assertThat(found.name()).isEqualTo("이후");
        assertThat(found.body()).isEqualTo("새 본문");
        assertThat(found.createdAt()).isEqualTo("2026-08-18T10:00:00");
        assertThat(found.updatedAt()).isEqualTo("2026-08-18T11:00:00");
    }

    @Test
    void deleteAndCountByOwner() {
        mapper.insert(row("t1", "u1", "A"));
        mapper.insert(row("t2", "u1", "B"));
        assertThat(mapper.countByOwner("u1")).isEqualTo(2);

        mapper.delete("t1");
        assertThat(mapper.find("t1")).isNull();
        assertThat(mapper.countByOwner("u1")).isEqualTo(1);
    }
}
