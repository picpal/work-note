package com.worknote.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 스위치를 켰을 때 프로퍼티 배선이 실제로 풀리는지. 서버는 띄우지 않는다(MOCK 환경) —
 * 키스토어 파일 없이 바인딩만 확인하는 게 목적이다.
 *
 * <p>스위치(server.ssl.enabled)와 인증서를 함께 준다 — 한쪽만 주면 기동 검증에서 실패한다.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite:file:tlswiring?mode=memory&cache=shared",
    "server.ssl.enabled=true",
    "server.ssl.key-store=/etc/worknote/worknote.p12",
    "server.ssl.key-store-password=changeit"
})
class TlsEnabledWiringTest {

    @Autowired ServerProperties server;

    @Test
    void tlsSwitchEnablesSslAndSecureCookie() {
        assertThat(server.getSsl()).isNotNull();
        assertThat(server.getSsl().isEnabled()).isTrue();
        assertThat(server.getSsl().getKeyStore()).isEqualTo("/etc/worknote/worknote.p12");
        assertThat(server.getSsl().getKeyStoreType()).isEqualTo("PKCS12");
        assertThat(server.getSsl().getKeyAlias()).isEqualTo("worknote");
        assertThat(server.getServlet().getSession().getCookie().getSecure()).isTrue();
    }
}
