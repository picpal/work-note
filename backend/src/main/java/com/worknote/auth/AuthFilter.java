package com.worknote.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * server 모드 세션 가드. 통과 시 request attribute에 UserRow를 싣는다. local 모드에선 미등록.
 * 매 요청 user + credential 2회 DB 조회 트레이드오프 — 비활성화·비밀번호 리셋 즉시 차단 우선 (pool=1·3~4팀 소규모 전제).
 */
public class AuthFilter extends OncePerRequestFilter {

    public static final String CURRENT_USER = "worknote.currentUser";
    private static final Set<String> ALLOWLIST = Set.of("/api/auth/login", "/api/auth/signup", "/api/health");

    private final UserMapper users;
    private final ObjectMapper json;

    public AuthFilter(UserMapper users, ObjectMapper json) {
        this.users = users;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        // contextPath 분리 — non-root context 배포 시 allowlist 불일치로 인한 전면 락아웃 방지
        String path = req.getRequestURI().substring(req.getContextPath().length());
        if (ALLOWLIST.contains(path)) {
            chain.doFilter(req, res);
            return;
        }
        HttpSession session = req.getSession(false);
        String userId = session != null ? (String) session.getAttribute(AuthController.SESSION_USER) : null;
        // 매 요청 DB 조회 — 세션 발급 후 비활성화된 사용자도 즉시 차단
        UserRow user = userId != null ? users.findById(userId) : null;
        if (user == null || !"active".equals(user.status()) || credChanged(session, user.id())) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write(json.writeValueAsString(Map.of("error", "인증이 필요합니다")));
            return;
        }
        req.setAttribute(CURRENT_USER, user);
        chain.doFilter(req, res);
    }

    /** 비밀번호 리셋 시 기존 세션 즉시 무효화 — 로그인 시점 salt(세션)와 현재 DB salt 불일치면 차단. credential 누락도 차단. */
    private boolean credChanged(HttpSession session, String userId) {
        CredentialRow cred = users.findCredential(userId);
        return cred == null || !cred.salt().equals(session.getAttribute(AuthController.SESSION_CRED));
    }
}
