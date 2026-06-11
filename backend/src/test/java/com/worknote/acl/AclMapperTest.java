package com.worknote.acl;

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
class AclMapperTest {
    @Autowired AclMapper acl;
    @Autowired TeamMapper teams;
    @Autowired NodeMapper nodes;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM acl");
        jdbc.update("DELETE FROM public_flag");
        jdbc.update("DELETE FROM team_member");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM node");
    }

    private void node(String id, String parentId, String type) {
        nodes.insert(new NodeRow(id, parentId, type, "N-" + id, 1, null, null, null, null));
    }

    @Test
    void ancestorChainSelfToRoot() {
        node("f1", null, "folder");
        node("f2", "f1", "folder");
        node("n1", "f2", "note");
        assertThat(acl.ancestorChain("n1")).containsExactly("n1", "f2", "f1");  // 자신→루트 순
        assertThat(acl.ancestorChain("f1")).containsExactly("f1");
    }

    @Test
    void aclInsertAndFindForNodes() {
        node("f1", null, "folder");
        node("n1", "f1", "note");
        acl.insertAcl(new AclRow("team", "t-pay", "f1", "edit"));
        acl.insertAcl(new AclRow("user", "u1", "n1", "deny"));
        List<AclRow> rows = acl.findAclForNodes(List.of("n1", "f1"));
        assertThat(rows).hasSize(2);
        assertThat(acl.findAllAcl()).hasSize(2);
    }

    @Test
    void publicFlagsRoundTrip() {
        node("f1", null, "folder");
        node("n1", "f1", "note");
        acl.insertPublicFlag("f1", "public");
        acl.insertPublicFlag("n1", "exclude");
        assertThat(acl.findPublicFlagsForNodes(List.of("n1", "f1"))).hasSize(2);
        assertThat(acl.findAllPublicFlags())
            .extracting(PublicFlagRow::mode).containsExactlyInAnyOrder("public", "exclude");
    }

    @Test
    void teamsOfUser() {
        teams.insertTeam("t-pay", "결제팀");
        teams.insertTeam("t-sec", "보안팀");
        teams.addMember("t-pay", "u1");
        teams.addMember("t-sec", "u1");
        teams.addMember("t-pay", "u2");
        assertThat(teams.teamsOf("u1")).containsExactlyInAnyOrder("t-pay", "t-sec");
        assertThat(teams.teamsOf("u-none")).isEmpty();
    }
}
