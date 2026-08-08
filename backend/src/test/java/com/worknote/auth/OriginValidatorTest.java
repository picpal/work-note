package com.worknote.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** T5 — Origin 검증 순수 로직. 배포별 호스트·포트 유도와 canonical 오버라이드 규칙. */
class OriginValidatorTest {

    private final OriginValidator v = new OriginValidator(null);

    private static MockHttpServletRequest req(String method) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, "/api/tree");
        r.setScheme("http");
        r.setServerName("localhost");
        r.setServerPort(80);
        return r;
    }

    @Test
    void sameOriginIsAllowedForEachMutatingMethod() {
        for (String m : new String[]{"POST", "PUT", "PATCH", "DELETE"}) {
            MockHttpServletRequest r = req(m);
            r.addHeader("Origin", "http://localhost");
            assertThat(v.allowed(r)).as(m).isTrue();
        }
    }

    @Test
    void crossOriginIsDenied() {
        MockHttpServletRequest r = req("POST");
        r.addHeader("Origin", "https://evil.domain.co.kr");
        assertThat(v.allowed(r)).isFalse();
    }

    @Test
    void literalNullOriginIsDenied() {
        MockHttpServletRequest r = req("POST");
        r.addHeader("Origin", "null");
        assertThat(v.allowed(r)).isFalse();
    }

    @Test
    void safeMethodsAreNotValidated() {
        for (String m : new String[]{"GET", "HEAD", "OPTIONS"}) {
            MockHttpServletRequest r = req(m);
            r.addHeader("Origin", "https://evil.domain.co.kr");
            assertThat(v.allowed(r)).as(m).isTrue();
        }
    }

    @Test
    void noHeadersPassesOnlyWithoutSessionCookie() {
        assertThat(v.allowed(req("POST"))).isTrue();
        MockHttpServletRequest withCookie = req("POST");
        withCookie.setRequestedSessionId("SID-1");
        assertThat(v.allowed(withCookie)).isFalse();
    }

    @Test
    void refererIsUsedOnlyWhenOriginAbsent() {
        MockHttpServletRequest ok = req("POST");
        ok.addHeader("Referer", "http://localhost/app/index.html");
        assertThat(v.allowed(ok)).isTrue();

        MockHttpServletRequest bad = req("POST");
        bad.addHeader("Referer", "https://evil.domain.co.kr/attack");
        assertThat(v.allowed(bad)).isFalse();

        // Origin이 있으면 Referer는 무시 — 공격자가 무해한 Referer를 붙여 우회하지 못하게
        MockHttpServletRequest both = req("POST");
        both.addHeader("Origin", "https://evil.domain.co.kr");
        both.addHeader("Referer", "http://localhost/app");
        assertThat(v.allowed(both)).isFalse();
    }

    @Test
    void malformedRefererIsDenied() {
        MockHttpServletRequest r = req("POST");
        r.addHeader("Referer", ":::not a url:::");
        assertThat(v.allowed(r)).isFalse();
    }

    @Test
    void defaultPortIsNormalizedButOtherPortsMustMatch() {
        MockHttpServletRequest explicit = req("POST");
        explicit.addHeader("Origin", "http://localhost:80");
        assertThat(v.allowed(explicit)).isTrue();   // :80은 http 기본 포트 — 동일 오리진

        MockHttpServletRequest onPort = req("POST");
        onPort.setServerPort(8080);
        onPort.addHeader("Origin", "http://localhost");
        assertThat(v.allowed(onPort)).isFalse();
        onPort.removeHeader("Origin");
        onPort.addHeader("Origin", "http://localhost:8080");
        assertThat(v.allowed(onPort)).isTrue();
    }

    @Test
    void canonicalOriginOverridesRequestDerived() {
        // 리버스 프록시 뒤에서 TLS 종단 시 요청 스킴(http)이 실제 접속 오리진(https)과 달라진다
        OriginValidator canonical = new OriginValidator("https://note.domain.co.kr/");
        MockHttpServletRequest self = req("POST");
        self.addHeader("Origin", "http://localhost");
        assertThat(canonical.allowed(self)).isFalse();

        MockHttpServletRequest proxied = req("POST");
        proxied.addHeader("Origin", "https://note.domain.co.kr");
        assertThat(canonical.allowed(proxied)).isTrue();
    }

    @Test
    void blankCanonicalFallsBackToRequestOrigin() {
        OriginValidator blank = new OriginValidator("   ");
        MockHttpServletRequest r = req("POST");
        r.addHeader("Origin", "http://localhost");
        assertThat(blank.allowed(r)).isTrue();
    }
}
