package com.worknote.audit;

import com.worknote.auth.UserRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared")
class AuditServiceTest {
    @Autowired AuditService audit;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() { jdbc.update("DELETE FROM audit_log"); }

    @Test
    void logWritesRow() {
        UserRow user = new UserRow("u1", "10001", null, "홍길동", "operator", "active", null);
        audit.log(user, "node.create", "n-123", "10.0.0.5");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM audit_log");
        assertThat(row.get("who")).isEqualTo("10001");
        assertThat(row.get("act")).isEqualTo("node.create");
        assertThat(row.get("target")).isEqualTo("n-123");
        assertThat(row.get("ip")).isEqualTo("10.0.0.5");
        assertThat((String) row.get("at")).isNotBlank();
    }

    @Test
    void logSkipsNullUser() {
        audit.log(null, "node.create", "n-123", "10.0.0.5");   // local 모드 — 감사 생략
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class)).isZero();
    }

    @Test
    void logRawWritesWithoutUser() {
        audit.logRaw("10001", "login.fail", null, "10.0.0.5");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class)).isEqualTo(1);
    }
}
