package com.worknote.audit;

import com.worknote.auth.UserRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDateTime;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
        // at은 ISO_LOCAL_DATE_TIME 포맷 — 파싱 가능해야 함 (포맷 일관성 박제)
        assertThatCode(() -> LocalDateTime.parse((String) row.get("at"))).doesNotThrowAnyException();
    }

    @Test
    void logSkipsNullUser() {
        audit.log(null, "node.create", "n-123", "10.0.0.5");   // local 모드 — 감사 생략
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class)).isZero();
    }

    @Test
    void logRawWritesWithoutUser() {
        // logRaw는 who NOT NULL 제약에 직접 노출되는 유일 경로 — 값까지 단언
        audit.logRaw("10001", "login.fail", null, "10.0.0.5");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM audit_log");
        assertThat(row.get("who")).isEqualTo("10001");
        assertThat(row.get("act")).isEqualTo("login.fail");
        assertThat(row.get("target")).isNull();
    }
}
