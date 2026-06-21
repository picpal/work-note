package com.worknote.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 전역 보안 응답 헤더. 모든 응답(정적 SPA·API·에러·401 포함)에 부착 — 체인 최선두에서 먼저 세팅.
 *
 * CSP 주의: 앱은 (1) HTML 엔트리의 인라인 테마-init 스크립트, (2) mermaid의 eval/Worker(blob) 사용 때문에
 * script-src에 'unsafe-inline' 'unsafe-eval' blob:을 둔다. 따라서 CSP의 실효 방어는 스크립트 주입 차단보다
 * default-src/connect-src(외부 출처·유출 차단)·object-src·base-uri·form-action·frame-ancestors(클릭재킹) 쪽.
 * XSS 1차 방어는 프런트 DOMPurify가 담당(이 CSP는 심층 방어). nonce 기반 script-src로 강화하려면 Vite 빌드 연동 필요.
 */
public class SecurityHeadersFilter implements Filter {

    private static final String CSP = String.join("; ",
        "default-src 'self'",
        "script-src 'self' 'unsafe-inline' 'unsafe-eval' blob:",   // 인라인 테마-init + mermaid eval/worker
        "style-src 'self' 'unsafe-inline'",                        // React 인라인 스타일 + mermaid SVG <style>
        "img-src 'self' data: blob:",                              // 첨부(self) + mermaid 데이터/blob
        "font-src 'self' data:",
        "connect-src 'self'",
        "worker-src 'self' blob:",                                 // mermaid ELK 레이아웃 워커
        "object-src 'none'",
        "base-uri 'self'",
        "form-action 'self'",
        "frame-ancestors 'none'");                                 // 클릭재킹 — X-Frame-Options와 정렬

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletResponse res = (HttpServletResponse) response;
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "DENY");                  // 앱은 자기 자신을 iframe하지 않음
        res.setHeader("Referrer-Policy", "no-referrer");           // 공유 토큰이 URL에 있으므로 Referer 유출 차단
        res.setHeader("Content-Security-Policy", CSP);
        chain.doFilter(request, response);
    }
}
