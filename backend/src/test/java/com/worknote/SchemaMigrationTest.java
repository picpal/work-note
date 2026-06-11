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
    void systemRolesSeeded() {
        var roles = jdbc.queryForList("SELECT id FROM role WHERE system = 1 ORDER BY id", String.class);
        assertThat(roles).containsExactly("admin", "operator", "visitor");
        String adminCaps = jdbc.queryForObject("SELECT caps FROM role WHERE id = 'admin'", String.class);
        assertThat(adminCaps).contains("admin.permissions").contains("res.share");
    }
}
