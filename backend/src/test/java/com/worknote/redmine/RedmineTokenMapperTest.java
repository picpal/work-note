package com.worknote.redmine;

import static org.assertj.core.api.Assertions.assertThat;
import com.worknote.auth.UserRow;
import com.worknote.auth.UserMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:memdb-redminetoken?mode=memory&cache=shared",
    "worknote.mode=server",
    "worknote.admin-password=x",
    "worknote.totp.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class RedmineTokenMapperTest {
    @Autowired RedmineTokenMapper mapper;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM user_redmine_token");
        jdbc.update("DELETE FROM app_user");
        users.insert(new UserRow("u1", "10001", "a@corp.local", "홍", "operator", "active", null));
    }

    @Test void upsert_find_delete_roundtrip() {
        mapper.upsert(new RedmineTokenRow("u1", "ENC", "jdoe", "2026-06-25T10:00:00", "2026-06-25T10:00:00"));
        RedmineTokenRow r = mapper.find("u1");
        assertThat(r).isNotNull();
        assertThat(r.tokenEnc()).isEqualTo("ENC");
        assertThat(r.redmineLogin()).isEqualTo("jdoe");

        mapper.upsert(new RedmineTokenRow("u1", "ENC2", "jdoe2", "2026-06-25T11:00:00", "2026-06-25T10:00:00"));
        assertThat(mapper.find("u1").tokenEnc()).isEqualTo("ENC2");

        mapper.delete("u1");
        assertThat(mapper.find("u1")).isNull();
    }
}
