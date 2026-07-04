package com.worknote.redmine;

import com.worknote.vault.VaultException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Redmine base URL SSRF 가드 (CWE-918, 감사 §2-2).
 * - scheme은 http/https만 (file/gopher/jar 차단), userinfo(user@host) 금지
 * - loopback · link-local(169.254.0.0/16 — 클라우드 메타데이터 포함) · any-local(0.0.0.0) · multicast 거부
 * - 사설 대역(10/8 등)은 허용 — 폐쇄망 인트라넷 Redmine이 정상 사용처(차단 시 기능 불능)
 * - DNS 미해석 호스트는 저장 시점엔 허용(폐쇄망 DNS 준비 전 설정 가능), 호출 시점 재검증이 최종 방어
 * - 호스트가 여러 주소로 해석되면(다중 A레코드) 전부 검사 — 하나라도 차단 대역이면 거부(리바인딩 표면 축소)
 * - 호출 시점 재검증은 resolve-then-connect라 TOCTOU 잔여 위험이 있으나(자바 HttpClient 제약)
 *   저장 후 DNS 레코드 변경(리바인딩) 시나리오를 실질 차단
 */
public final class RedmineUrlValidator {
    private RedmineUrlValidator() {}

    /** 저장 시점 검증 — 형식·scheme 위반 422. 해석되는 호스트는 차단 대역 검사까지. */
    public static void validateForSave(String baseUrl) {
        URI uri = parse(baseUrl);
        try {
            assertAllAllowed(InetAddress.getAllByName(hostForResolve(uri)));
        } catch (UnknownHostException e) {
            // 미해석은 저장 허용 — validateForFetch가 호출 시점에 재검증
        }
    }

    /** 호출 시점 재검증 — 모든 위반을 Upstream(502)으로. 미해석도 여기선 차단. */
    public static void validateForFetch(String baseUrl) {
        URI uri;
        try {
            uri = parse(baseUrl);
        } catch (VaultException e) {
            throw new RedmineException.Upstream("redmine_base_invalid");
        }
        try {
            assertAllAllowed(InetAddress.getAllByName(hostForResolve(uri)));
        } catch (UnknownHostException e) {
            throw new RedmineException.Upstream("redmine_base_unresolved");
        } catch (VaultException e) {
            throw new RedmineException.Upstream("redmine_base_blocked");
        }
    }

    private static URI parse(String baseUrl) {
        URI uri;
        try {
            uri = URI.create(baseUrl == null ? "" : baseUrl.trim());
        } catch (IllegalArgumentException e) {
            throw VaultException.invalid("올바른 URL이 아닙니다");
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw VaultException.invalid("http 또는 https URL만 허용됩니다");
        }
        if (uri.getUserInfo() != null) {
            throw VaultException.invalid("URL에 인증정보(user@host)를 포함할 수 없습니다");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw VaultException.invalid("호스트가 없는 URL입니다");
        }
        return uri;
    }

    /** 해석된 주소 전부 검사 — 하나라도 차단 대역이면 거부(다중 A레코드 리바인딩 표면 축소). */
    static void assertAllAllowed(InetAddress[] addrs) {
        if (addrs == null || addrs.length == 0) {
            throw VaultException.invalid("호스트를 해석할 수 없습니다");   // fail-closed: 빈 순회의 암묵적 허용 차단
        }
        for (InetAddress addr : addrs) {
            assertAllowed(addr);
        }
    }

    /** 차단 대역 검사 — 위반 시 VaultException.invalid. */
    private static void assertAllowed(InetAddress addr) {
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()
            || addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
            throw VaultException.invalid("허용되지 않는 대역의 호스트입니다");
        }
    }

    /** URI.getHost()는 IPv6 리터럴을 브래킷 포함([::1])으로 돌려줌 — InetAddress 해석 전 제거(JDK strip 미보장). */
    private static String hostForResolve(URI uri) {
        String host = uri.getHost();
        if (host != null && host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }
}
