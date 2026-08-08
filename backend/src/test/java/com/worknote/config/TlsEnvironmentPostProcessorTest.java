package com.worknote.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * TLS/쿠키 정합성 게이트. 유도하는 값은 세션 쿠키 secure 하나뿐이고, 나머지는 검증이다.
 * 서버는 기동하지 않는다 — 프로퍼티 해석만 검사한다.
 *
 * <p>핵심 계약: 쿠키 secure는 <b>실효 TLS 상태</b>를 따르고, 모순된 명시 설정은 부팅을 실패시킨다.
 * 조용히 어긋난 채 뜨면 (a) HTTPS인데 평문 쿠키 노출 또는 (b) HTTP인데 Secure 쿠키 → 로그인 전면 장애다.
 */
class TlsEnvironmentPostProcessorTest {

    private static final String COOKIE_SECURE = "server.servlet.session.cookie.secure";

    private ConfigurableEnvironment envWith(String... kv) {
        Map<String, Object> props = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            props.put(kv[i], kv[i + 1]);
        }
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        new TlsEnvironmentPostProcessor().postProcessEnvironment(env, null);
        return env;
    }

    // --- 무설정 = 기존 HTTP 동작 그대로 ---------------------------------------------------------

    @Test
    void nothingConfigured_cookieNotSecure() {
        ConfigurableEnvironment env = envWith();
        assertThat(env.getProperty(COOKIE_SECURE, Boolean.class)).isFalse();
    }

    /** application.yml의 빈 플레이스홀더(WORKNOTE_TLS_* 미지정)도 무설정과 동일해야 한다. */
    @Test
    void emptyPlaceholders_cookieNotSecure() {
        ConfigurableEnvironment env = envWith(
            "server.ssl.key-store", "",
            "server.ssl.enabled", "false",
            "worknote.canonical-origin", "");
        assertThat(env.getProperty(COOKIE_SECURE, Boolean.class)).isFalse();
    }

    // --- 앱이 TLS를 종단하는 경우 ---------------------------------------------------------------

    @Test
    void tlsEnabledWithKeystore_cookieSecure() {
        ConfigurableEnvironment env = envWith(
            "server.ssl.enabled", "true",
            "server.ssl.key-store", "/etc/worknote/worknote.p12");
        assertThat(env.getProperty(COOKIE_SECURE, Boolean.class)).isTrue();
    }

    /** SSL 번들로 켠 배포도 쿠키가 Secure여야 한다 — key-store 유무로 TLS를 추측하면 여기서 뚫린다. */
    @Test
    void tlsEnabledWithBundle_cookieSecure() {
        ConfigurableEnvironment env = envWith(
            "server.ssl.enabled", "true",
            "server.ssl.bundle", "worknote-web");
        assertThat(env.getProperty(COOKIE_SECURE, Boolean.class)).isTrue();
    }

    /** PEM(certificate/private-key) 설정도 동일. */
    @Test
    void tlsEnabledWithPemCertificate_cookieSecure() {
        ConfigurableEnvironment env = envWith(
            "server.ssl.enabled", "true",
            "server.ssl.certificate", "classpath:worknote.crt");
        assertThat(env.getProperty(COOKIE_SECURE, Boolean.class)).isTrue();
    }

    // --- 모순된 명시 설정은 부팅 실패 -----------------------------------------------------------

    /** 키스토어만 넣고 스위치를 안 켠 상태 — 평문으로 뜨는 대신 실패시킨다. */
    @Test
    void tlsMaterialWithoutEnabled_failsStartup() {
        assertThatThrownBy(() -> envWith("server.ssl.key-store", "/etc/worknote/worknote.p12"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("WORKNOTE_TLS_ENABLED");
    }

    @Test
    void sslBundleWithoutEnabled_failsStartup() {
        assertThatThrownBy(() -> envWith("server.ssl.bundle", "worknote-web"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("WORKNOTE_TLS_ENABLED");
    }

    /** 스위치만 켜고 인증서를 안 준 경우 — 컨테이너 기동 시점의 난해한 오류 대신 여기서 잡는다. */
    @Test
    void tlsEnabledWithoutMaterial_failsStartup() {
        assertThatThrownBy(() -> envWith("server.ssl.enabled", "true"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("WORKNOTE_TLS_KEYSTORE");
    }

    /** 앱이 HTTPS를 종단하는데 canonical-origin은 http — CSRF 기준과 쿠키가 동시에 어긋난다. */
    @Test
    void tlsEnabledWithHttpCanonicalOrigin_failsStartup() {
        assertThatThrownBy(() -> envWith(
            "server.ssl.enabled", "true",
            "server.ssl.key-store", "/etc/worknote/worknote.p12",
            "worknote.canonical-origin", "http://note.domain.co.kr"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("canonical-origin");
    }

    /** HTTPS 배포에서 쿠키 secure를 끄는 오버라이드 = 세션 쿠키 평문 노출. */
    @Test
    void explicitInsecureCookieUnderHttps_failsStartup() {
        assertThatThrownBy(() -> envWith(
            "worknote.canonical-origin", "https://note.domain.co.kr",
            COOKIE_SECURE, "false"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(COOKIE_SECURE);
    }

    /** 평문 HTTP 배포에서 Secure 쿠키 = 쿠키 미전송 로그인 전면 장애. */
    @Test
    void explicitSecureCookieOnPlainHttp_failsStartup() {
        assertThatThrownBy(() -> envWith(COOKIE_SECURE, "true"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(COOKIE_SECURE);
    }

    /** 프록시 헤더를 신뢰하는 배포는 요청 스킴이 https로 인식되므로 Secure 명시가 모순이 아니다. */
    @Test
    void explicitSecureCookieWithForwardHeaders_isAllowed() {
        ConfigurableEnvironment env = envWith(
            "server.forward-headers-strategy", "framework",
            COOKIE_SECURE, "true");
        assertThat(env.getProperty(COOKIE_SECURE, Boolean.class)).isTrue();
    }

    /** forward-headers만으로는 쿠키 secure를 강제하지 않는다 — 프록시가 평문일 수도 있어 컨테이너가 요청별로 판단. */
    @Test
    void forwardHeadersAlone_doesNotForceSecureCookie() {
        ConfigurableEnvironment env = envWith("server.forward-headers-strategy", "framework");
        assertThat(env.getProperty(COOKIE_SECURE, Boolean.class)).isFalse();
    }

    // --- 프록시가 TLS를 종단하는 경우 -----------------------------------------------------------

    @Test
    void httpsCanonicalOrigin_cookieSecureWithoutLocalTls() {
        ConfigurableEnvironment env = envWith("worknote.canonical-origin", "https://note.domain.co.kr");
        assertThat(env.getProperty("server.ssl.enabled", Boolean.class)).isNull();
        assertThat(env.getProperty(COOKIE_SECURE, Boolean.class)).isTrue();
    }

    @Test
    void httpCanonicalOrigin_cookieNotSecure() {
        ConfigurableEnvironment env = envWith("worknote.canonical-origin", "http://note.domain.co.kr");
        assertThat(env.getProperty(COOKIE_SECURE, Boolean.class)).isFalse();
    }

    // --- canonical-origin 형식 검증 (조용한 요청 유도 폴백 차단) ---------------------------------

    @Test
    void malformedCanonicalOrigin_failsStartup() {
        for (String bad : new String[]{"https://", "note.domain.co.kr", ":::not a url:::", "ftp://note.domain.co.kr"}) {
            assertThatThrownBy(() -> envWith("worknote.canonical-origin", bad))
                .as(bad)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WORKNOTE_CANONICAL_ORIGIN");
        }
    }

    @Test
    void wellFormedCanonicalOriginVariantsAreAccepted() {
        for (String ok : new String[]{
            "https://note.domain.co.kr", "https://note.domain.co.kr/", "http://10.1.2.3:8080"}) {
            assertThatCode(() -> envWith("worknote.canonical-origin", ok)).as(ok).doesNotThrowAnyException();
        }
    }
}
