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

/** server 모드 세션 가드. 통과 시 request attribute에 UserRow를 싣는다. local 모드에선 미등록. */
public class AuthFilter extends OncePerRequestFilter {

    public static final String CURRENT_USER = "worknote.currentUser";
    private static final Set<String> ALLOWLIST = Set.of("/api/auth/login", "/api/health");

    private final UserMapper users;
    private final ObjectMapper json;

    public AuthFilter(UserMapper users, ObjectMapper json) {
        this.users = users;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (ALLOWLIST.contains(req.getRequestURI())) {
            chain.doFilter(req, res);
            return;
        }
        HttpSession session = req.getSession(false);
        String userId = session != null ? (String) session.getAttribute(AuthController.SESSION_USER) : null;
        // 매 요청 DB 조회 — 세션 발급 후 비활성화된 사용자도 즉시 차단
        UserRow user = userId != null ? users.findById(userId) : null;
        if (user == null || !"active".equals(user.status())) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write(json.writeValueAsString(Map.of("error", "인증이 필요합니다")));
            return;
        }
        req.setAttribute(CURRENT_USER, user);
        chain.doFilter(req, res);
    }
}
