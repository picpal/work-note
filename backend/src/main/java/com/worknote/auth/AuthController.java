package com.worknote.auth;

import com.worknote.audit.AuditService;
import com.worknote.auth.dto.ChangePasswordRequest;
import com.worknote.auth.dto.LoginRequest;
import com.worknote.auth.dto.MeResponse;
import com.worknote.auth.dto.SignupRequest;
import com.worknote.auth.dto.TotpVerifyRequest;
import com.worknote.auth.dto.UpdateProfileRequest;
import com.worknote.auth.totp.Totp2faPolicy;
import com.worknote.auth.totp.TotpService;
import com.worknote.vault.VaultException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;

/** 세션 기반 인증 API. server 모드에선 AuthFilter가 login/signup/health 외 전부를 가드한다. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public static final String SESSION_USER = "worknote.userId";
    /** 로그인 시점 credential salt — AuthFilter가 매 요청 현재 DB salt와 비교해 리셋된 세션을 즉시 무효화. */
    public static final String SESSION_CRED = "worknote.credSalt";
    /** 부분 인증 세션 마커 — 비밀번호 OK but 2FA 미완. 완전 인증 전까지 API 차단. */
    public static final String SESSION_2FA_PENDING = "worknote.2faPending";

    private final AuthService auth;
    private final RoleCaps roleCaps;
    private final AuditService audit;
    private final TotpService totpService;
    private final UserMapper users;
    private final com.worknote.setting.SettingService settings;
    private final Clock clock;
    private final boolean serverMode;

    public AuthController(AuthService auth, RoleCaps roleCaps, AuditService audit,
                          TotpService totpService, UserMapper users,
                          com.worknote.setting.SettingService settings, Clock clock,
                          @Value("${worknote.mode:local}") String mode) {
        this.auth = auth;
        this.roleCaps = roleCaps;
        this.audit = audit;
        this.totpService = totpService;
        this.users = users;
        this.settings = settings;
        this.clock = clock;
        this.serverMode = "server".equals(mode);
    }

    @PostMapping("/login")
    public Object login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        AuthService.AuthUser result;
        try {
            result = auth.login(req.emp(), req.password());
        } catch (AuthException e) {
            audit.logRaw(req.emp(), "login.fail", null, http.getRemoteAddr());   // 실패도 항상 기록 (스펙 §7)
            throw e;
        }
        HttpSession session = http.getSession(true);
        http.changeSessionId();   // 세션 고정 방어 — 공용 PC 교대 로그인 시 세션 id 재사용 방지 (내용 유지, id만 교체)
        session.setAttribute(SESSION_USER, result.user().id());

        // admin grace_start 보장 — 최초 로그인 시 기록 (2FA 강제 유예 시작 시점)
        boolean isAdmin = result.caps().containsAll(com.worknote.acl.AclResolver.ADMIN_CAPS);
        if (isAdmin && users.findGraceStart(result.user().id()) == null
                && !totpService.isEnabled(result.user().id())) {
            users.setGraceStart(result.user().id(),
                LocalDateTime.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            audit.logRaw(result.user().emp(), "2fa.grace_start", null, http.getRemoteAddr());
        }

        if (totpService.isEnabled(result.user().id())) {
            session.setAttribute(SESSION_2FA_PENDING, Boolean.TRUE);   // 부분 인증 — SESSION_CRED 미설정
            audit.logRaw(result.user().emp(), "2fa.challenge", null, http.getRemoteAddr());
            return Map.of("status", "2fa_required");
        }
        session.setAttribute(SESSION_CRED, result.credSalt());
        audit.logRaw(result.user().emp(), "login.success", null, http.getRemoteAddr());
        return toMe(result.user(), result.caps());
    }

    @PostMapping("/2fa/verify")
    public MeResponse verify2fa(@Valid @RequestBody TotpVerifyRequest req, HttpServletRequest http) {
        HttpSession session = http.getSession(false);
        String userId = session != null ? (String) session.getAttribute(SESSION_USER) : null;
        if (userId == null || !Boolean.TRUE.equals(session.getAttribute(SESSION_2FA_PENDING))) {
            throw AuthException.unauthorized("2FA 인증 대기 상태가 아닙니다");
        }
        if (!totpService.verifyLogin(userId, req.code())) {
            audit.logRaw(userId, "2fa.verify.fail", null, http.getRemoteAddr());
            throw AuthException.unauthorized("인증 코드가 올바르지 않습니다");
        }
        return completePending(session, userId, http, "2fa.verify.success");
    }

    /** 부분 인증 → 완전 인증 승격 (TOTP verify 및 복구 경로 공용). */
    private MeResponse completePending(HttpSession session, String userId, HttpServletRequest http, String act) {
        UserRow user = users.findById(userId);
        CredentialRow cred = users.findCredential(userId);
        if (user == null || cred == null) {
            throw AuthException.unauthorized("자격 정보가 유효하지 않습니다");
        }
        http.changeSessionId();   // 권한 상승 시점 세션 재발급 (defense-in-depth, OWASP 세션 고정 방어)
        session.removeAttribute(SESSION_2FA_PENDING);
        session.setAttribute(SESSION_CRED, cred.salt());
        audit.logRaw(user.emp(), act, null, http.getRemoteAddr());
        return toMe(user, auth.caps(user));
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> signup(@Valid @RequestBody SignupRequest req, HttpServletRequest http) {
        UserRow user;
        try {
            user = auth.signup(req.emp(), req.name(), req.email(), req.password());
        } catch (VaultException e) {
            audit.logRaw(req.emp(), "signup.fail", null, http.getRemoteAddr());   // 실패도 항상 기록 (login.fail과 동일 패턴)
            throw e;
        }
        audit.logRaw(user.emp(), "signup", null, http.getRemoteAddr());
        return Map.of("id", user.id(), "status", user.status());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest http) {
        HttpSession session = http.getSession(false);
        if (session != null) {
            // local 모드는 CURRENT_USER가 없어 log가 skip — server 모드만 기록
            UserRow user = (UserRow) http.getAttribute(AuthFilter.CURRENT_USER);
            audit.log(user, "logout", null, http.getRemoteAddr());
            session.invalidate();
        }
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest req, HttpServletRequest http) {
        UserRow user = (UserRow) http.getAttribute(AuthFilter.CURRENT_USER);
        if (user == null) {
            // server 모드는 AuthFilter가 먼저 401 — 여기 도달은 local 모드(무인증, 본인 개념 없음)
            throw AuthException.forbidden("비밀번호 변경은 로그인 상태에서만 가능합니다");
        }
        String newSalt = auth.changePassword(user.id(), req.currentPassword(), req.newPassword());
        HttpSession session = http.getSession(false);
        if (session != null) {
            session.setAttribute(SESSION_CRED, newSalt);   // 본인 현재 세션 유지 (AuthFilter credChanged 통과)
        }
        audit.log(user, "auth.password.change", null, http.getRemoteAddr());
    }

    @PostMapping("/update-profile")
    public MeResponse updateProfile(@Valid @RequestBody UpdateProfileRequest req, HttpServletRequest http) {
        UserRow user = (UserRow) http.getAttribute(AuthFilter.CURRENT_USER);
        if (user == null) {
            // server 모드는 AuthFilter가 먼저 401 — 여기 도달은 local 모드(무인증, 본인 개념 없음)
            throw AuthException.forbidden("프로필 변경은 로그인 상태에서만 가능합니다");
        }
        UserRow updated = auth.updateProfile(user.id(), req.name(), req.email());
        audit.log(user, "auth.profile.update", null, http.getRemoteAddr());
        return toMe(updated, auth.caps(updated));
    }

    @GetMapping("/me")
    public MeResponse me(HttpServletRequest http) {
        UserRow user = (UserRow) http.getAttribute(AuthFilter.CURRENT_USER);
        if (user != null) {
            return toMe(user, auth.caps(user));
        }
        if (serverMode) {
            // server 모드에선 필터가 먼저 401을 반환 — 방어적 가드
            throw AuthException.unauthorized("인증이 필요합니다");
        }
        // 1단계 호환 — caps도 실제 admin 시드로 채움 (프런트 caps 기반 UI 가드가 모드 무관하게 동작)
        // local 모드는 2FA 무관 — TotpInfo 전부 false
        return new MeResponse("local", "local", "local", null, "admin", roleCaps.of("admin"),
            new MeResponse.TotpInfo(false, false, false, false));
    }

    private MeResponse toMe(UserRow user, Set<String> caps) {
        boolean enabled = totpService.isEnabled(user.id());
        boolean isAdmin = caps.containsAll(com.worknote.acl.AclResolver.ADMIN_CAPS);
        boolean enforced = Totp2faPolicy.enforced(isAdmin, enabled);
        String graceStart = users.findGraceStart(user.id());
        boolean graceExpired = enforced && Totp2faPolicy.graceExpired(
            graceStart == null ? null : LocalDateTime.parse(graceStart),
            settings.graceDays(), LocalDateTime.now(clock));
        boolean emailPresent = user.email() != null && !user.email().isBlank();
        return new MeResponse(user.id(), user.emp(), user.name(), user.email(), user.roleId(), caps,
            new MeResponse.TotpInfo(enabled, enforced, graceExpired, emailPresent));
    }
}
