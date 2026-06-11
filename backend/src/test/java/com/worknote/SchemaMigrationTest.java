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
}
