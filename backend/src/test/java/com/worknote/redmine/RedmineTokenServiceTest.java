package com.worknote.redmine;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.worknote.auth.UserRow;
import com.worknote.auth.UserMapper;
import com.worknote.setting.SettingService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:memdb-redminetoksvc?mode=memory&cache=shared",
    "worknote.mode=server", "worknote.admin-password=x",
    "worknote.totp.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class RedmineTokenServiceTest {
    @Autowired RedmineTokenService svc;
    @Autowired SettingService settings;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;
    @MockBean RedmineClient client;

    UserRow u1;
    @BeforeEach void clean() {
        jdbc.update("DELETE FROM user_redmine_token");
        jdbc.update("DELETE FROM app_user");
        u1 = new UserRow("u1","10001","a@corp.local","홍","operator","active",null);
        users.insert(u1);
        settings.setRedmine(true, "http://redmine.intra");
    }

    @Test void setToken_validates_encrypts_and_stores_login() {
        when(client.fetchCurrentLogin(eq("http://redmine.intra"), eq("KEY"))).thenReturn("jdoe");
        svc.setToken(u1, "KEY");
        assertThat(svc.hasToken("u1")).isTrue();
        assertThat(svc.status("u1").redmineLogin()).isEqualTo("jdoe");
        assertThat(svc.tokenFor("u1")).isEqualTo("KEY");           // 복호화 라운드트립
        assertThat(svc.status("u1").tokenEnc()).isNotEqualTo("KEY"); // 평문 아님
    }

    @Test void setToken_invalid_key_throws_auth() {
        when(client.fetchCurrentLogin(anyString(), anyString()))
            .thenThrow(new RedmineException.Auth("redmine_token_invalid"));
        assertThatThrownBy(() -> svc.setToken(u1, "BAD"))
            .isInstanceOf(RedmineException.Auth.class);
        assertThat(svc.hasToken("u1")).isFalse();
    }

    @Test void setToken_disabled_throws() {
        settings.setRedmine(false, "");
        assertThatThrownBy(() -> svc.setToken(u1, "KEY")).isInstanceOf(RedmineException.NotFound.class);
    }
}
