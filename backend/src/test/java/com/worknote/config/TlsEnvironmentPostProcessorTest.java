package com.worknote.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * M-4 TLS 앱측 준비 — 스위치 하나(WORKNOTE_TLS_KEYSTORE → server.ssl.key-store)에서
 * server.ssl.enabled와 세션 쿠키 secure가 함께 유도되는지. 서버는 기동하지 않는다.
 */
class TlsEnvironmentPostProcessorTest {

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

    @Test
    void keystoreUnset_tlsOffAndCookieNotSecure() {
        ConfigurableEnvironment env = envWith("server.ssl.key-store", "");
        assertThat(env.getProperty("server.ssl.enabled", Boolean.class)).isFalse();
        assertThat(env.getProperty("server.servlet.session.cookie.secure", Boolean.class)).isFalse();
    }

    @Test
    void keystoreAbsentEntirely_tlsOff() {
        ConfigurableEnvironment env = envWith();
        assertThat(env.getProperty("server.ssl.enabled", Boolean.class)).isFalse();
        assertThat(env.getProperty("server.servlet.session.cookie.secure", Boolean.class)).isFalse();
    }

    @Test
    void keystoreSet_tlsOnAndCookieSecure() {
        ConfigurableEnvironment env = envWith("server.ssl.key-store", "/etc/worknote/worknote.p12");
        assertThat(env.getProperty("server.ssl.enabled", Boolean.class)).isTrue();
        assertThat(env.getProperty("server.servlet.session.cookie.secure", Boolean.class)).isTrue();
    }

    /** 리버스 프록시가 TLS를 종단하면 앱은 평문 HTTP지만 브라우저 오리진은 https — 쿠키는 secure여야 한다. */
    @Test
    void httpsCanonicalOrigin_cookieSecureWithoutLocalTls() {
        ConfigurableEnvironment env = envWith("worknote.canonical-origin", "https://note.domain.co.kr");
        assertThat(env.getProperty("server.ssl.enabled", Boolean.class)).isFalse();
        assertThat(env.getProperty("server.servlet.session.cookie.secure", Boolean.class)).isTrue();
    }

    @Test
    void httpCanonicalOrigin_cookieNotSecure() {
        ConfigurableEnvironment env = envWith("worknote.canonical-origin", "http://note.domain.co.kr");
        assertThat(env.getProperty("server.servlet.session.cookie.secure", Boolean.class)).isFalse();
    }

    /** 유도값은 최저 우선순위 — 운영자가 --server.ssl.enabled=false로 명시하면 그쪽이 이긴다. */
    @Test
    void explicitPropertyWinsOverDerivedDefault() {
        ConfigurableEnvironment env = envWith(
            "server.ssl.key-store", "/etc/worknote/worknote.p12",
            "server.ssl.enabled", "false");
        assertThat(env.getProperty("server.ssl.enabled", Boolean.class)).isFalse();
    }
}
