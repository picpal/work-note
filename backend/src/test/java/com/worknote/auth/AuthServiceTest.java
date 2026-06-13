package com.worknote.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.worknote.vault.VaultException;
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
        assertThat(unknown).isInstanceOf(AuthException.class)
            .satisfies(e -> assertThat(((AuthException) e).status()).isEqualTo(AuthException.Status.UNAUTHORIZED));
    }

    @Test
    void loginFailureDoesNotStampLastLogin() {
        createUser("u1", "10001", "operator", "active", "pw-1234");
        catchThrowable(() -> auth.login("10001", "nope"));
        assertThat(users.findById("u1").lastLogin()).isNull();
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

    @Test
    void changePasswordReplacesCredentialAndReturnsNewSalt() {
        createUser("u1", "10001", "operator", "active", "pw-current");
        String oldSalt = users.findCredential("u1").salt();
        String newSalt = auth.changePassword("u1", "pw-current", "new-pw-9999");
        assertThat(newSalt).isNotEqualTo(oldSalt);
        assertThat(users.findCredential("u1").salt()).isEqualTo(newSalt);
        assertThat(auth.login("10001", "new-pw-9999").user().id()).isEqualTo("u1");
    }

    @Test
    void changePasswordWrongCurrentIs422() {
        createUser("u1", "10001", "operator", "active", "pw-current");
        assertThatThrownBy(() -> auth.changePassword("u1", "WRONG", "new-pw-9999"))
            .isInstanceOf(VaultException.class)
            .satisfies(e -> assertThat(((VaultException) e).status()).isEqualTo(VaultException.Status.INVALID));
    }

    @Test
    void changePasswordShortNewIs422() {
        createUser("u1", "10001", "operator", "active", "pw-current");
        assertThatThrownBy(() -> auth.changePassword("u1", "pw-current", "short"))
            .isInstanceOf(VaultException.class)
            .satisfies(e -> assertThat(((VaultException) e).status()).isEqualTo(VaultException.Status.INVALID));
    }

    @Test
    void updateProfileChangesNameAndEmail() {
        createUser("u1", "10001", "operator", "active", "pw-current");
        UserRow updated = auth.updateProfile("u1", "새이름", "new@corp.local");
        assertThat(updated.name()).isEqualTo("새이름");
        assertThat(updated.email()).isEqualTo("new@corp.local");
        UserRow row = users.findById("u1");
        assertThat(row.name()).isEqualTo("새이름");
        assertThat(row.email()).isEqualTo("new@corp.local");
        assertThat(row.roleId()).isEqualTo("operator");
        assertThat(row.status()).isEqualTo("active");
    }

    @Test
    void updateProfileBlankEmailNormalizesToNull() {
        createUser("u1", "10001", "operator", "active", "pw-current");
        UserRow updated = auth.updateProfile("u1", "이름", "   ");
        assertThat(updated.email()).isNull();
        assertThat(users.findById("u1").email()).isNull();
    }

    @Test
    void updateProfileBlankNameIs422() {
        createUser("u1", "10001", "operator", "active", "pw-current");
        assertThatThrownBy(() -> auth.updateProfile("u1", "  ", "x@corp.local"))
            .isInstanceOf(VaultException.class)
            .satisfies(e -> assertThat(((VaultException) e).status()).isEqualTo(VaultException.Status.INVALID));
    }
}
