package com.worknote.auth;

import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * CSRF 방어 — 상태 변경 요청의 Origin(없으면 Referer)이 이 서버의 오리진과 일치하는지 검증.
 * CSRF 토큰을 쓰지 않는 이유: 단일 오리진 앱이라 오리진 검증으로 충분하고,
 * 토큰은 프런트 저장·수명 관리·재발급을 모두 유발한다.
 *
 * <p>SameSite=Lax만으로 부족한 이유: 등록가능 도메인이 같은 형제 서브도메인(xxx.domain.co.kr)은
 * same-site로 취급돼 세션 쿠키가 그대로 실려 나간다.
 */
public final class OriginValidator {

    /** 상태 변경 메서드만 검증 — GET/HEAD/OPTIONS는 부작용이 없다는 전제(상태변경 GET은 별건). */
    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** null이면 요청 자체에서 유도 — 배포마다 호스트·포트가 달라 하드코딩할 수 없다. */
    private final String canonicalOrigin;

    /** 비어 있으면 요청 유도, 값이 있으면 형식 검증 후 정규화 — 깨진 값을 조용히 폴백시키지 않는다. */
    public OriginValidator(String canonicalOrigin) {
        String c = canonicalOrigin == null ? "" : canonicalOrigin.trim();
        this.canonicalOrigin = c.isEmpty() ? null : requireCanonicalOrigin(c);
    }

    /**
     * 설정된 canonical-origin을 검증·정규화한다. 형식이 깨졌으면 예외 — 예전엔 파싱 실패 시 null이 되어
     * 요청 유도 오리진으로 조용히 되돌아갔고, 프록시 뒤 배포가 잘못된 CSRF 기준으로 도는데 신호가 없었다.
     *
     * <p>기동 시점(TlsEnvironmentPostProcessor)과 필터 생성 시점 양쪽에서 같은 규칙을 쓰도록 static으로 노출.
     */
    public static String requireCanonicalOrigin(String value) {
        String raw = value == null ? "" : value.trim();
        String normalized = normalize(raw);
        // 브라우저 Origin 헤더는 http/https만 — 다른 스킴은 어떤 요청과도 일치하지 않아 전면 403이 된다.
        if (normalized == null
                || !(normalized.startsWith("http://") || normalized.startsWith("https://"))) {
            throw new IllegalArgumentException(
                "WORKNOTE_CANONICAL_ORIGIN 형식이 올바르지 않습니다: \"" + raw + "\". "
                + "스킴+호스트[:포트] 형태의 절대 오리진이어야 합니다 (예: https://note.domain.co.kr). "
                + "프록시가 TLS를 종단하지 않는 배포라면 값을 비워 두십시오 — 요청에서 유도합니다.");
        }
        return normalized;
    }

    public boolean allowed(HttpServletRequest req) {
        if (!MUTATING.contains(req.getMethod().toUpperCase(Locale.ROOT))) return true;

        String expected = canonicalOrigin != null
            ? canonicalOrigin
            : origin(req.getScheme(), req.getServerName(), req.getServerPort());
        if (expected == null) return false;

        String origin = header(req, "Origin");
        if (origin != null) {
            // Origin이 있으면 Referer는 보지 않는다 — 무해한 Referer를 덧붙인 우회 차단.
            // "null"(샌드박스 iframe·리다이렉트 세탁)은 파싱에 실패해 그대로 거부된다.
            return expected.equals(normalize(origin));
        }
        String referer = header(req, "Referer");
        if (referer != null) {
            return expected.equals(normalize(referer));
        }
        // 둘 다 없음: 브라우저는 cross-origin 상태 변경에 Origin을 반드시 붙인다.
        // 세션 쿠키까지 실려 있는데 헤더가 없으면 브라우저가 보낸 요청이 아니다 → 거부.
        // 쿠키도 없으면 통과 — 인증이 필요한 경로는 어차피 뒤에서 401이다.
        return req.getRequestedSessionId() == null;
    }

    private static String header(HttpServletRequest req, String name) {
        String v = req.getHeader(name);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /** 오리진 문자열(또는 URL)을 스킴+호스트+포트로 정규화. 파싱 불가·스킴/호스트 누락이면 null. */
    private static String normalize(String value) {
        try {
            URI uri = new URI(value);
            return origin(uri.getScheme(), uri.getHost(), uri.getPort());
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** 기본 포트(http 80 / https 443)는 생략해 "http://h"와 "http://h:80"이 같게 비교되도록. */
    private static String origin(String scheme, String host, int port) {
        if (scheme == null || host == null) return null;
        String s = scheme.toLowerCase(Locale.ROOT);
        String h = host.toLowerCase(Locale.ROOT);
        boolean defaultPort = port < 0
            || ("http".equals(s) && port == 80)
            || ("https".equals(s) && port == 443);
        return defaultPort ? s + "://" + h : s + "://" + h + ":" + port;
    }
}
