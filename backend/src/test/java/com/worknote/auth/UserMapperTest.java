package com.worknote.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared")
class UserMapperTest {
    @Autowired UserMapper mapper;
    @Autowired RoleMapper roleMapper;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM user_credential");
        jdbc.update("DELETE FROM app_user");
    }

    private UserRow user(String id, String emp, String roleId, String status) {
        return new UserRow(id, emp, emp + "@corp.local", "이름-" + emp, roleId, status, null);
    }

    @Test
    void insertAndFindByEmp() {
        mapper.insert(user("u1", "10001", "operator", "active"));
        UserRow found = mapper.findByEmp("10001");
        assertThat(found.id()).isEqualTo("u1");
        assertThat(found.roleId()).isEqualTo("operator");
        assertThat(mapper.findByEmp("99999")).isNull();
    }

    @Test
    void findByIdAndCount() {
        assertThat(mapper.countUsers()).isZero();
        mapper.insert(user("u1", "10001", "operator", "active"));
        assertThat(mapper.findById("u1").emp()).isEqualTo("10001");
        assertThat(mapper.countUsers()).isEqualTo(1);
    }

    @Test
    void credentialRoundTrip() {
        mapper.insert(user("u1", "10001", "operator", "active"));
        mapper.insertCredential(new CredentialRow("u1", "c2FsdA==", "aGFzaA=="));
        CredentialRow cred = mapper.findCredential("u1");
        assertThat(cred.salt()).isEqualTo("c2FsdA==");
        assertThat(cred.passwordHash()).isEqualTo("aGFzaA==");
        assertThat(mapper.findCredential("none")).isNull();
    }

    @Test
    void stampLastLogin() {
        mapper.insert(user("u1", "10001", "operator", "active"));
        mapper.stampLastLogin("u1", "2026-06-11T10:00:00");
        assertThat(mapper.findById("u1").lastLogin()).isEqualTo("2026-06-11T10:00:00");
    }

    @Test
    void roleSeedReadable() {
        RoleRow admin = roleMapper.findById("admin");
        assertThat(admin.system()).isEqualTo(1);
        assertThat(admin.caps()).contains("admin.audit");
        assertThat(roleMapper.findById("visitor").caps()).contains("res.read");
    }
}
