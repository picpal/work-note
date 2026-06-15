package com.worknote.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 콘텐츠 해시 SPA 캐시 정책.
 * - /assets/** : 해시가 곧 버전이므로 1년 immutable (재검증 없음).
 * - / · *.html : 매 요청 재검증(no-cache) — 재배포 시 옛 번들 고착 방지.
 * - 그 외(/api/** 등) : 무간섭(헤더 미설정).
 */
public class StaticCacheFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String uri = ((HttpServletRequest) request).getRequestURI();
        HttpServletResponse res = (HttpServletResponse) response;
        if (uri.startsWith("/assets/")) {
            res.setHeader("Cache-Control", "public, max-age=31536000, immutable");
        } else if (uri.equals("/") || uri.endsWith(".html")) {
            res.setHeader("Cache-Control", "no-cache, must-revalidate");
        }
        chain.doFilter(request, response);
    }
}
