package com.worknote.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * 환경변수 미지정 = 기존 HTTP 동작 그대로. application.yml에 server.ssl 블록이 생겼다는 이유만으로
 * TLS가 켜지거나 쿠키에 secure가 붙으면(HTTP 배포에서 쿠키 미전송 → 전면 장애) 안 된다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:file:tlsdefaults?mode=memory&cache=shared")
class TlsDefaultsTest {

    @Autowired Environment env;
    @Autowired ServerProperties server;

    @Test
    void tlsIsOffByDefault() {
        assertThat(env.getProperty("server.ssl.enabled", Boolean.class)).isFalse();
        assertThat(server.getSsl() == null || !server.getSsl().isEnabled()).isTrue();
    }

    @Test
    void sessionCookieIsNotSecureByDefault() {
        assertThat(server.getServlet().getSession().getCookie().getSecure()).isFalse();
    }

    @Test
    void sessionCookieKeepsHttpOnlyAndLax() {
        assertThat(server.getServlet().getSession().getCookie().getHttpOnly()).isTrue();
        assertThat(server.getServlet().getSession().getCookie().getSameSite().attributeValue()).isEqualTo("Lax");
    }
}
