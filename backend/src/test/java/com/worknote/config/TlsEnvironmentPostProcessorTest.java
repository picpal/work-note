package com.worknote.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    /**
     * 프록시 종단 배포의 같은 모순 — 쿠키 secure=true는 "브라우저가 HTTPS로 본다"는 주장이고
     * canonical-origin=http는 "브라우저 오리진은 HTTP"라는 선언이다. 둘은 프록시 전략과 무관하게 모순이다.
     * 뜨고 나면 OriginValidator가 https Origin을 http 기준과 비교해 로그인 포함 전 요청을 403으로 막는다.
     */
    @Test
    void httpCanonicalOriginWithSecureCookie_failsStartupEvenBehindProxyHeaders() {
        assertThatThrownBy(() -> envWith(
            "server.forward-headers-strategy", "framework",
            "worknote.canonical-origin", "http://note.domain.co.kr",
            COOKIE_SECURE, "true"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("canonical-origin");

        // forward-headers 없이도 동일 — 모순의 근거는 프록시 설정이 아니다.
        assertThatThrownBy(() -> envWith(
            "worknote.canonical-origin", "http://note.domain.co.kr",
            COOKIE_SECURE, "true"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("canonical-origin");
    }

    /** 반대로 https 주장이 하나도 없으면 http canonical-origin은 정상 설정이다 — 과잉 실패 금지. */
    @Test
    void httpCanonicalOriginBehindProxyHeaders_startsWhenNoHttpsIsClaimed() {
        ConfigurableEnvironment env = envWith(
            "server.forward-headers-strategy", "framework",
            "worknote.canonical-origin", "http://note.domain.co.kr");
        assertThat(env.getProperty(COOKIE_SECURE, Boolean.class)).isFalse();
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

    // --- 배포 조합 전수표 -----------------------------------------------------------------------

    /**
     * {TLS, canonical-origin, 쿠키 secure 오버라이드, forward-headers} 전 조합의 기동 여부 명세.
     *
     * <p>실패시키는 기준은 <b>선언끼리의 모순</b> 하나다. "브라우저가 HTTPS로 본다"는 주장(TLS 활성 또는
     * 쿠키 secure=true)과 canonical-origin의 스킴이 어긋나거나, HTTPS인데 쿠키 secure를 끄면 실패다.
     * forward-headers는 어느 쪽 증거도 아니라서 판정에 넣지 않는다 — 프록시 헤더를 믿는다는 뜻일 뿐
     * 프록시가 HTTPS를 쓴다는 뜻이 아니다. 다만 "HTTPS 신호가 하나도 없는데 쿠키만 secure=true"인
     * 경우에만, 요청 스킴이 프록시 헤더로 https가 되는 배포를 막지 않기 위해 예외로 둔다.
     *
     * <p>뜨는 조합은 전부 <b>선언이 사실이면 실제로 동작한다</b>. 선언이 거짓인 경우(예: 프록시는
     * HTTPS인데 canonical-origin을 http로 적음)는 설정만으로 판별할 수 없어 여기서 막지 않는다.
     */
    private static final String[] MATRIX = {
        // tls, canonical-origin, 쿠키 secure 오버라이드, forward-headers, 기대
        "off,none,unset,off,start",    // 무설정 로컬/사내 HTTP — 기존 동작
        "off,none,unset,on,start",     // 프록시 종단 + 헤더 신뢰 (canonical 없이도 요청에서 유도)
        "off,none,true,off,fail",      // HTTPS 신호 없음 + Secure 쿠키 = 로그인 전면 장애
        "off,none,true,on,start",      // 헤더로 스킴이 https가 되는 배포 — 명시 Secure 허용
        "off,none,false,off,start",
        "off,none,false,on,start",
        "off,http,unset,off,start",    // 평문 HTTP 배포를 명시 선언
        "off,http,unset,on,start",
        "off,http,true,off,fail",      // secure=true(HTTPS 주장) vs origin http — 정면 모순
        "off,http,true,on,fail",       // 프록시 설정과 무관하게 같은 모순
        "off,http,false,off,start",
        "off,http,false,on,start",
        "off,https,unset,off,start",   // 프록시 TLS 종단 정석 — 쿠키 secure 자동
        "off,https,unset,on,start",
        "off,https,true,off,start",
        "off,https,true,on,start",
        "off,https,false,off,fail",    // HTTPS인데 쿠키 평문 노출
        "off,https,false,on,fail",
        "on,none,unset,off,start",     // 앱이 직접 TLS 종단
        "on,none,unset,on,start",
        "on,none,true,off,start",
        "on,none,true,on,start",
        "on,none,false,off,fail",
        "on,none,false,on,fail",
        "on,http,unset,off,fail",      // TLS 종단인데 오리진은 http
        "on,http,unset,on,fail",
        "on,http,true,off,fail",
        "on,http,true,on,fail",
        "on,http,false,off,fail",
        "on,http,false,on,fail",
        "on,https,unset,off,start",
        "on,https,unset,on,start",
        "on,https,true,off,start",
        "on,https,true,on,start",
        "on,https,false,off,fail",
        "on,https,false,on,fail",
    };

    @Test
    void deploymentMatrixStartsOrFailsAsSpecified() {
        for (String row : MATRIX) {
            String[] f = row.split(",");
            String tls = f[0];
            String origin = f[1];
            String cookie = f[2];
            String proxy = f[3];
            boolean expectStart = "start".equals(f[4]);

            List<String> kv = new ArrayList<>();
            if ("on".equals(tls)) {
                kv.addAll(List.of("server.ssl.enabled", "true",
                    "server.ssl.key-store", "/etc/worknote/worknote.p12"));
            }
            if (!"none".equals(origin)) {
                kv.addAll(List.of("worknote.canonical-origin", origin + "://note.domain.co.kr"));
            }
            if (!"unset".equals(cookie)) {
                kv.addAll(List.of(COOKIE_SECURE, cookie));
            }
            if ("on".equals(proxy)) {
                kv.addAll(List.of("server.forward-headers-strategy", "framework"));
            }
            String[] props = kv.toArray(String[]::new);

            if (!expectStart) {
                assertThatThrownBy(() -> envWith(props)).as(row).isInstanceOf(IllegalStateException.class);
                continue;
            }
            ConfigurableEnvironment env = envWith(props);
            // 뜨는 조합의 쿠키 secure: 명시값이 있으면 그대로, 없으면 실효 HTTPS 상태를 따른다.
            boolean expectedSecure = "unset".equals(cookie)
                ? "on".equals(tls) || "https".equals(origin)
                : Boolean.parseBoolean(cookie);
            assertThat(env.getProperty(COOKIE_SECURE, Boolean.class)).as(row).isEqualTo(expectedSecure);
        }
    }
}
