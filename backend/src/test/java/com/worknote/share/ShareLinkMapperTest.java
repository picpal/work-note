package com.worknote.share;

import com.worknote.vault.NodeMapper;
import com.worknote.vault.NodeRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared")
class ShareLinkMapperTest {
    @Autowired ShareLinkMapper mapper;
    @Autowired NodeMapper nodes;
    @Autowired JdbcTemplate jdbc;

    static final String NOW = "2026-06-12T10:00:00";

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM share_link");
        jdbc.update("DELETE FROM node");
    }

    private void note(String id) {
        nodes.insert(new NodeRow(id, null, "note", "N-" + id, 1, "body", NOW, null, null));
    }

    /** 활성 기본형: 미취소·미만료·열람 무제한 */
    private ShareLinkRow link(String id, String nodeId) {
        return new ShareLinkRow(id, "tok-" + id, nodeId, "emp1",
            "2026-06-12T09:00:00", "2026-06-19T09:00:00", null, 0, null, null);
    }

    @Test
    void insertAndFindRoundTrip() {
        note("sl-n1");
        ShareLinkRow row = new ShareLinkRow("s1", "tok-s1", "sl-n1", "emp1",
            "2026-06-12T09:00:00", "2026-06-19T09:00:00", 5, 0, "[\"emp2\",\"emp3\"]", null);
        mapper.insert(row);

        assertThat(mapper.findByToken("tok-s1")).isEqualTo(row);
        ShareLinkRow byId = mapper.findById("s1");
        assertThat(byId).isEqualTo(row);
        assertThat(byId.viewCount()).isZero();
        assertThat(mapper.findByToken("no-such-token")).isNull();
    }

    @Test
    void findActiveByNodeExcludesExpiredRevokedAndExhausted() {
        note("sl-n1");
        mapper.insert(link("s-active", "sl-n1"));
        mapper.insert(new ShareLinkRow("s-expired", "tok-expired", "sl-n1", "emp1",
            "2026-06-01T09:00:00", "2026-06-08T09:00:00", null, 0, null, null));       // 만료
        mapper.insert(new ShareLinkRow("s-revoked", "tok-revoked", "sl-n1", "emp1",
            "2026-06-12T09:00:00", "2026-06-19T09:00:00", null, 0, null, NOW));        // 취소
        mapper.insert(new ShareLinkRow("s-exhausted", "tok-exhausted", "sl-n1", "emp1",
            "2026-06-12T09:00:00", "2026-06-19T09:00:00", 3, 3, null, null));          // 열람 소진

        assertThat(mapper.findActiveByNode("sl-n1", NOW))
            .extracting(ShareLinkRow::id).containsExactly("s-active");
    }

    @Test
    void incrementViewCountAndFindAllActiveJoinsNode() {
        note("sl-n1");
        mapper.insert(link("s1", "sl-n1"));
        mapper.incrementViewCount("s1");

        List<ActiveShareRow> all = mapper.findAllActive(NOW);
        assertThat(all).hasSize(1);
        ActiveShareRow row = all.get(0);
        assertThat(row.nodeName()).isEqualTo("N-sl-n1");
        assertThat(row.nodeDeletedAt()).isNull();
        assertThat(row.viewCount()).isEqualTo(1);

        // 휴지통 노드의 링크도 포함 — suspend 표시용 (결정 S14)
        note("sl-n2");
        mapper.insert(link("s2", "sl-n2"));
        nodes.softDeleteSubtree("sl-n2", NOW, "emp1");
        List<ActiveShareRow> withTrashed = mapper.findAllActive(NOW);
        assertThat(withTrashed).extracting(ActiveShareRow::id).containsExactlyInAnyOrder("s1", "s2");
        assertThat(withTrashed.stream().filter(r -> r.id().equals("s2")).findFirst().orElseThrow()
            .nodeDeletedAt()).isEqualTo(NOW);
    }

    @Test
    void deleteInRemovesLinksOfNodes() {
        note("sl-n1");
        note("sl-n2");
        note("sl-n3");
        mapper.insert(link("s1", "sl-n1"));
        mapper.insert(link("s2", "sl-n2"));
        mapper.insert(link("s3", "sl-n3"));

        mapper.deleteIn(List.of("sl-n1", "sl-n2"));

        assertThat(mapper.findById("s1")).isNull();
        assertThat(mapper.findById("s2")).isNull();
        assertThat(mapper.findById("s3")).isNotNull();
    }

    @Test
    void revokeMakesLinkInactive() {
        note("sl-n1");
        mapper.insert(link("s1", "sl-n1"));
        mapper.revoke("s1", NOW);

        assertThat(mapper.findById("s1").revokedAt()).isEqualTo(NOW);
        assertThat(mapper.findActiveByNode("sl-n1", NOW)).isEmpty();
    }
}
