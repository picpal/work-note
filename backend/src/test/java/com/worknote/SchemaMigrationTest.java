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
    void v4_createsAttachmentAndSettingTables() {
        var tables = jdbc.queryForList(
            "SELECT name FROM sqlite_master WHERE type='table'", String.class);
        assertThat(tables).contains("attachment", "app_setting");
        var indexes = jdbc.queryForList(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='attachment'", String.class);
        assertThat(indexes).contains("idx_attachment_node");
        // seed 2건
        String exts = jdbc.queryForObject(
            "SELECT value FROM app_setting WHERE key='upload.allowed_ext'", String.class);
        String max = jdbc.queryForObject(
            "SELECT value FROM app_setting WHERE key='upload.max_bytes'", String.class);
        assertThat(exts).contains("png").contains("pdf");
        assertThat(max).isEqualTo("26214400");
    }

    @Test
    void systemRolesSeeded() {
        var roles = jdbc.queryForList("SELECT id FROM role WHERE system = 1 ORDER BY id", String.class);
        assertThat(roles).containsExactly("admin", "operator", "visitor");
        String adminCaps = jdbc.queryForObject("SELECT caps FROM role WHERE id = 'admin'", String.class);
        assertThat(adminCaps).contains("admin.permissions").contains("res.share");
    }

    @Test
    void v9_addsTotpTables() {
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='user_totp'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='totp_recovery'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT value FROM app_setting WHERE key='2fa.grace_days'", String.class)).isEqualTo("7");
        // app_user.totp_grace_start 컬럼 존재 (PRAGMA)
        assertThat(jdbc.queryForList("PRAGMA table_info(app_user)").stream()
            .anyMatch(r -> "totp_grace_start".equals(r.get("name")))).isTrue();
    }

    @Test
    void v10_renamesOperatorRoleLabel() {
        // id는 'operator' 그대로, 표시명만 '일반사용자'로 변경 (V10)
        assertThat(jdbc.queryForObject("SELECT name FROM role WHERE id = 'operator'", String.class))
            .isEqualTo("일반사용자");
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
