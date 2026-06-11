package com.worknote.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** server 모드 세션 가드 — 등록·검증 로직은 Task 6에서 완성. */
public class AuthFilter extends OncePerRequestFilter {

    public static final String CURRENT_USER = "worknote.currentUser";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(req, res);   // Task 6에서 구현
    }
}
