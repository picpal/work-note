package com.worknote.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file::memory:?cache=shared")
class AuthServiceTest {
    @Autowired AuthService auth;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM user_credential");
        jdbc.update("DELETE FROM app_user");
    }

    private void createUser(String id, String emp, String roleId, String status, String password) {
        users.insert(new UserRow(id, emp, null, "이름-" + emp, roleId, status, null));
        String salt = PasswordHasher.newSalt();
        users.insertCredential(new CredentialRow(id, salt, PasswordHasher.hash(password, salt)));
    }

    @Test
    void loginSuccessReturnsUserAndCapsAndStampsLastLogin() {
        createUser("u1", "10001", "operator", "active", "pw-1234");
        AuthService.AuthUser result = auth.login("10001", "pw-1234");
        assertThat(result.user().id()).isEqualTo("u1");
        assertThat(result.caps()).contains("res.edit").doesNotContain("admin.users");
        assertThat(users.findById("u1").lastLogin()).isNotNull();
    }

    @Test
    void loginWrongPasswordIs401() {
        createUser("u1", "10001", "operator", "active", "pw-1234");
        assertThatThrownBy(() -> auth.login("10001", "nope"))
            .isInstanceOf(AuthException.class)
            .satisfies(e -> assertThat(((AuthException) e).status()).isEqualTo(AuthException.Status.UNAUTHORIZED));
    }

    @Test
    void loginUnknownEmpIs401SameMessage() {
        createUser("u1", "10001", "operator", "active", "pw-1234");
        Throwable unknown = catchThrowable(() -> auth.login("99999", "pw-1234"));
        Throwable wrongPw = catchThrowable(() -> auth.login("10001", "nope"));
        assertThat(unknown.getMessage()).isEqualTo(wrongPw.getMessage());  // 계정 존재 노출 금지
    }

    @Test
    void loginPendingUserIs403() {
        createUser("u1", "10001", "operator", "pending", "pw-1234");
        assertThatThrownBy(() -> auth.login("10001", "pw-1234"))
            .isInstanceOf(AuthException.class)
            .satisfies(e -> assertThat(((AuthException) e).status()).isEqualTo(AuthException.Status.FORBIDDEN));
    }

    @Test
    void capsParsesAdminRole() {
        createUser("u1", "10001", "admin", "active", "pw-1234");
        assertThat(auth.login("10001", "pw-1234").caps())
            .contains("admin.users", "admin.permissions", "admin.roles", "admin.security", "admin.audit");
    }

    @Test
    void unknownRoleYieldsEmptyCaps() {
        createUser("u1", "10001", "ghost-role", "active", "pw-1234");
        assertThat(auth.login("10001", "pw-1234").caps()).isEmpty();
    }
}
