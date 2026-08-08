package com.worknote.config;

import com.worknote.auth.OriginValidator;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * TLS/쿠키 정합성 게이트. 하는 일은 둘뿐이다 — <b>실효 설정 검증</b>과 세션 쿠키 {@code Secure}
 * <b>기본값 하나</b> 유도.
 *
 * <p>이전 구현은 키스토어 <i>경로가 비어 있지 않다</i>는 사실에서 TLS 의도를 추측하고
 * {@code server.ssl.enabled}까지 만들어 냈다. 그래서 명시 설정과 어긋나면 조용히 모순 상태로 떴다 —
 * 키스토어 + {@code server.ssl.enabled=false}면 평문인데 쿠키만 {@code Secure}(로그인 전면 장애),
 * SSL 번들/PEM로 켠 TLS는 키스토어가 없어 쿠키가 평문 노출. 추측을 걷어내고 스위치를 명시로 바꿨다:
 * TLS는 {@code WORKNOTE_TLS_ENABLED}, 그 외 조합은 검증 대상이다.
 *
 * <p>쿠키 {@code Secure}는 "브라우저가 HTTPS로 접속하는가"를 따른다. 두 경로가 있다:
 * 앱이 직접 종단({@code server.ssl.enabled=true})하거나, 프록시가 종단하고
 * {@code worknote.canonical-origin}이 {@code https://}로 선언된 경우다. 후자는 추측이 아니라 선언이다 —
 * canonical-origin은 브라우저가 보는 오리진 그 자체이고, 그 배포는 CSRF 검증 때문에 어차피 필수 설정이다.
 *
 * <p>유도값은 <b>가장 낮은 우선순위</b>로 넣어 명시 설정이 이긴다. 다만 위험한 모순
 * (HTTPS인데 {@code secure=false}, 평문 HTTP인데 {@code secure=true})은 여기서 기동을 실패시킨다 —
 * 조용한 로그인 장애·세션 노출보다 뜨지 않는 편이 낫다.
 */
public class TlsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "worknote-tls-derived";
    static final String COOKIE_SECURE = "server.servlet.session.cookie.secure";
    private static final String TLS_ENABLED = "server.ssl.enabled";

    /** TLS 자재 — 하나라도 있으면 TLS를 켤 수 있는 상태. key-store만 보면 번들·PEM 배포를 놓친다. */
    private static final String[] TLS_MATERIAL = {
        "server.ssl.key-store", "server.ssl.bundle", "server.ssl.certificate"};

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean tlsEnabled = environment.getProperty(TLS_ENABLED, Boolean.class, false);
        boolean tlsMaterial = false;
        for (String key : TLS_MATERIAL) {
            tlsMaterial |= !text(environment, key).isEmpty();
        }

        String canonical = canonicalOrigin(environment);
        boolean canonicalHttps = canonical != null && canonical.startsWith("https://");

        if (tlsMaterial && !tlsEnabled) {
            throw new IllegalStateException(
                "TLS 설정이 모순됩니다: 키스토어/번들/PEM 인증서가 지정됐는데 " + TLS_ENABLED + "=false 입니다. "
                + "TLS로 서비스하려면 WORKNOTE_TLS_ENABLED=true 를 설정하고, "
                + "평문 HTTP로 둘 생각이면 TLS 자재 설정을 지우십시오.");
        }
        if (tlsEnabled && !tlsMaterial) {
            throw new IllegalStateException(
                "TLS를 켰는데(" + TLS_ENABLED + "=true) 서버 인증서가 없습니다. "
                + "WORKNOTE_TLS_KEYSTORE(+WORKNOTE_TLS_KEYSTORE_PASSWORD) 또는 "
                + "server.ssl.bundle / server.ssl.certificate 중 하나를 지정하십시오.");
        }
        // 앱이 HTTPS를 종단하는데 선언된 오리진이 http면 CSRF 기준과 쿠키가 동시에 어긋난다.
        if (tlsEnabled && canonical != null && !canonicalHttps) {
            throw new IllegalStateException(
                "TLS를 켰는데 worknote.canonical-origin이 http 입니다: " + canonical + ". "
                + "WORKNOTE_CANONICAL_ORIGIN을 https:// 로 고치거나, 프록시 종단이 아니라면 비워 두십시오.");
        }

        boolean externalHttps = tlsEnabled || canonicalHttps;
        Boolean explicitSecure = environment.getProperty(COOKIE_SECURE, Boolean.class);

        if (externalHttps && Boolean.FALSE.equals(explicitSecure)) {
            throw new IllegalStateException(
                "HTTPS 배포인데 " + COOKIE_SECURE + "=false 로 덮어썼습니다 — 세션 쿠키가 평문 구간에 실려 나갑니다. "
                + "이 오버라이드를 제거하십시오(실효 TLS 상태를 따라 자동으로 켜집니다).");
        }
        // 평문 HTTP에 Secure 쿠키를 붙이면 브라우저가 쿠키를 아예 안 보내 로그인이 전면 실패한다.
        // 프록시 헤더를 신뢰하는 배포는 요청 스킴이 https로 인식되므로 예외 — 그쪽은 컨테이너가 요청별로 판단한다.
        if (!externalHttps && Boolean.TRUE.equals(explicitSecure) && !trustsProxyHeaders(environment)) {
            throw new IllegalStateException(
                "평문 HTTP 배포인데 " + COOKIE_SECURE + "=true 입니다 — 브라우저가 세션 쿠키를 보내지 않아 로그인이 실패합니다. "
                + "프록시가 TLS를 종단한다면 WORKNOTE_CANONICAL_ORIGIN=https://... 또는 "
                + "server.forward-headers-strategy 를 설정하고, 아니면 이 오버라이드를 제거하십시오.");
        }

        environment.getPropertySources().addLast(
            new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of(COOKIE_SECURE, externalHttps)));
    }

    /** 검증까지 OriginValidator와 같은 규칙을 쓴다 — 기준이 두 벌이 되면 한쪽만 통과하는 값이 생긴다. */
    private static String canonicalOrigin(ConfigurableEnvironment environment) {
        String raw = text(environment, "worknote.canonical-origin");
        if (raw.isEmpty()) return null;
        try {
            return OriginValidator.requireCanonicalOrigin(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** none/미설정이면 프록시 헤더를 안 믿는 것 — 요청 스킴은 그대로 http다. */
    private static boolean trustsProxyHeaders(ConfigurableEnvironment environment) {
        String strategy = text(environment, "server.forward-headers-strategy").toLowerCase(Locale.ROOT);
        return !strategy.isEmpty() && !"none".equals(strategy);
    }

    private static String text(ConfigurableEnvironment environment, String key) {
        return environment.getProperty(key, "").trim();
    }

    /** application.yml이 로드된 뒤여야 실효 설정(server.ssl.*, worknote.*)을 읽을 수 있다. */
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
