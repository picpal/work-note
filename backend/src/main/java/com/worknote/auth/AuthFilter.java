package com.worknote.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.worknote.acl.AclResolver;
import com.worknote.auth.totp.Totp2faPolicy;
import com.worknote.auth.totp.TotpService;
import com.worknote.setting.SettingMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * server 모드 세션 가드. 통과 시 request attribute에 UserRow를 싣는다. local 모드에선 미등록.
 * 매 요청 user + credential 2회 DB 조회 트레이드오프 — 비활성화·비밀번호 리셋 즉시 차단 우선 (pool=1·3~4팀 소규모 전제).
 */
public class AuthFilter extends OncePerRequestFilter {

    public static final String CURRENT_USER = "worknote.currentUser";

    /** 인증 없이 접근 가능한 경로. logout은 포함하지 않음 — 완전 인증 세션 감사 기록 보존을 위해 필터 통과. */
    private static final Set<String> ALLOWLIST = Set.of(
        "/api/auth/login", "/api/auth/signup", "/api/health",
        "/api/auth/2fa/verify", "/api/auth/2fa/recover/request", "/api/auth/2fa/recover/verify");

    /** 부분 인증(pending) 세션도 통과 허용 — pending 사용자의 로그아웃 지원. */
    private static final Set<String> PENDING_ALLOWLIST = Set.of("/api/auth/logout");

    /** enforced admin이 grace 만료 후에도 접근 가능한 경로 (2FA 등록 플로우). */
    private static final Set<String> ENFORCE_ALLOWLIST = Set.of(
        "/api/auth/me", "/api/auth/logout",
        "/api/me/2fa/setup", "/api/me/2fa/qr", "/api/me/2fa/confirm", "/api/me/2fa");

    private final UserMapper users;
    private final ObjectMapper json;
    private final TotpService totpService;
    private final RoleCaps roleCaps;
    private final SettingMapper settingMapper;
    private final Clock clock;

    public AuthFilter(UserMapper users, ObjectMapper json,
                      TotpService totpService, RoleCaps roleCaps,
                      SettingMapper settingMapper, Clock clock) {
        this.users = users;
        this.json = json;
        this.totpService = totpService;
        this.roleCaps = roleCaps;
        this.settingMapper = settingMapper;
        this.clock = clock;
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

        // logout은 pending/완전인증 모두 허용 — 세션 사용자 로드해 감사 기록 후 통과
        if (PENDING_ALLOWLIST.contains(path)) {
            UserRow logoutUser = userId != null ? users.findById(userId) : null;
            if (logoutUser != null) req.setAttribute(CURRENT_USER, logoutUser);
            chain.doFilter(req, res);
            return;
        }

        // 부분 인증 세션 차단 — pending 상태면 verify/recover 외 모두 차단
        // credChanged보다 먼저 검사해 명시적 메시지 반환 (SESSION_CRED 미설정 → credChanged도 true지만 메시지 우선)
        if (session != null && Boolean.TRUE.equals(session.getAttribute(AuthController.SESSION_2FA_PENDING))) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write(json.writeValueAsString(Map.of("error", "2fa_required")));
            return;
        }

        // 매 요청 DB 조회 — 세션 발급 후 비활성화된 사용자도 즉시 차단
        UserRow user = userId != null ? users.findById(userId) : null;
        if (user == null || !"active".equals(user.status()) || credChanged(session, user.id())) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write(json.writeValueAsString(Map.of("error", "인증이 필요합니다")));
            return;
        }

        // admin 2FA 강제 블록 — grace 만료 후 ENFORCE_ALLOWLIST 외 경로 차단 (403, on401 로그아웃 방지)
        Set<String> caps = roleCaps.of(user.roleId());
        boolean isAdmin = caps.containsAll(AclResolver.ADMIN_CAPS);
        boolean totpEnabled = totpService.isEnabled(user.id());
        if (Totp2faPolicy.enforced(isAdmin, totpEnabled)) {
            String graceStart = users.findGraceStart(user.id());
            boolean expired = Totp2faPolicy.graceExpired(
                graceStart == null ? null : LocalDateTime.parse(graceStart),
                graceDays(), LocalDateTime.now(clock));
            if (expired && !ENFORCE_ALLOWLIST.contains(path)) {
                res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write(json.writeValueAsString(Map.of("error", "2fa_enrollment_required")));
                return;
            }
        }

        req.setAttribute(CURRENT_USER, user);
        chain.doFilter(req, res);
    }

    /** 비밀번호 리셋 시 기존 세션 즉시 무효화 — 로그인 시점 salt(세션)와 현재 DB salt 불일치면 차단. credential 누락도 차단. */
    private boolean credChanged(HttpSession session, String userId) {
        CredentialRow cred = users.findCredential(userId);
        return cred == null || !cred.salt().equals(session.getAttribute(AuthController.SESSION_CRED));
    }

    private int graceDays() {
        String v = settingMapper.get("2fa.grace_days");
        return v == null ? 7 : Integer.parseInt(v.trim());
    }
}
