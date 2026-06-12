package com.worknote;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared")
class SchemaMigrationTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void nodeAndTagTablesExist() {
        var tables = jdbc.queryForList(
            "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('node','tag')", String.class);
        assertThat(tables).containsExactlyInAnyOrder("node", "tag");
    }

    @Test
    void phase2TablesExist() {
        var tables = jdbc.queryForList(
            "SELECT name FROM sqlite_master WHERE type='table'", String.class);
        assertThat(tables).contains("role", "app_user", "user_credential",
            "team", "team_member", "space", "acl", "public_flag", "audit_log");
    }

    @Test
    void shareLinkTableExists() {
        var tables = jdbc.queryForList(
            "SELECT name FROM sqlite_master WHERE type='table'", String.class);
        assertThat(tables).contains("share_link");
        var indexes = jdbc.queryForList(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='share_link'", String.class);
        assertThat(indexes).contains("idx_share_link_node");
    }

    @Test
    void systemRolesSeeded() {
        var roles = jdbc.queryForList("SELECT id FROM role WHERE system = 1 ORDER BY id", String.class);
        assertThat(roles).containsExactly("admin", "operator", "visitor");
        String adminCaps = jdbc.queryForObject("SELECT caps FROM role WHERE id = 'admin'", String.class);
        assertThat(adminCaps).contains("admin.permissions").contains("res.share");
    }

    @Test
    void seedRoleCaps_allKnownToWhitelist() throws Exception {
        // 시드 드리프트 가드: 새 cap을 시드에 추가하고 RoleAdminService.KNOWN_CAPS 갱신을 빠뜨리면 즉시 검출
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String id : java.util.List.of("admin", "operator", "visitor")) {
            String caps = jdbc.queryForObject("SELECT caps FROM role WHERE id = ?", String.class, id);
            java.util.Set<String> parsed = json.readValue(caps,
                new com.fasterxml.jackson.core.type.TypeReference<java.util.Set<String>>() {});
            assertThat(com.worknote.admin.RoleAdminService.KNOWN_CAPS)
                .as("시드 역할 %s의 caps는 전부 KNOWN_CAPS에 있어야 한다", id)
                .containsAll(parsed);
        }
    }
}
