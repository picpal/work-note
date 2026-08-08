package com.worknote.config;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * M-4 — TLS 스위치 하나에서 파생되는 설정을 채운다.
 *
 * <p>스위치는 {@code WORKNOTE_TLS_KEYSTORE}({@code server.ssl.key-store}) 하나다.
 * TLS를 켜는 것과 세션 쿠키에 {@code Secure}를 붙이는 것을 운영자가 따로 켜게 두면 한쪽을 빠뜨린다 —
 * 켜는 걸 빠뜨리면 평문, {@code Secure}만 하드코딩하면 HTTP 배포에서 쿠키가 아예 안 실려 전면 장애다.
 *
 * <p>리버스 프록시가 TLS를 종단하면 앱은 평문 HTTP로 받지만 브라우저 입장에선 HTTPS다. 이 경우
 * {@code worknote.canonical-origin}이 {@code https://}로 시작하는 것을 TLS 신호로 삼아 쿠키만 Secure로 만든다.
 * (그 배포는 CSRF Origin 검증 때문에 canonical-origin 지정이 어차피 필수다 — 운영 가이드 §2 참조)
 *
 * <p>추가하는 프로퍼티 소스는 <b>가장 낮은 우선순위</b>다. 운영자가 {@code --server.ssl.enabled=...}로
 * 명시하면 그쪽이 이긴다 — 유도값은 어디까지나 기본값이다.
 */
public class TlsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "worknote-tls-derived";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String keyStore = environment.getProperty("server.ssl.key-store", "").trim();
        String canonicalOrigin = environment.getProperty("worknote.canonical-origin", "").trim();

        boolean localTls = !keyStore.isEmpty();
        boolean browserTls = localTls
            || canonicalOrigin.toLowerCase(Locale.ROOT).startsWith("https://");

        Map<String, Object> derived = new HashMap<>();
        derived.put("server.ssl.enabled", localTls);
        derived.put("server.servlet.session.cookie.secure", browserTls);
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, derived));
    }

    /** application.yml이 로드된 뒤여야 server.ssl.key-store 플레이스홀더를 읽을 수 있다. */
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
