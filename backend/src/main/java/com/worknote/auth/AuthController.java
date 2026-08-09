package com.worknote.auth;

import com.worknote.audit.AuditService;
import com.worknote.auth.dto.ChangePasswordRequest;
import com.worknote.auth.dto.LoginRequest;
import com.worknote.auth.dto.MeResponse;
import com.worknote.auth.dto.SignupRequest;
import com.worknote.auth.dto.TotpVerifyRequest;
import com.worknote.auth.dto.UpdateProfileRequest;
import com.worknote.auth.dto.RecoverRequest;
import com.worknote.auth.dto.RecoverVerifyRequest;
import com.worknote.auth.totp.RecoveryService;
import com.worknote.auth.totp.Totp2faPolicy;
import com.worknote.auth.totp.TotpService;
import com.worknote.redmine.RedmineTokenService;
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
    private final RecoveryService recoveryService;
    private final UserMapper users;
    private final com.worknote.setting.SettingService settings;
    private final RedmineTokenService redmineTokens;
    private final Clock clock;
    private final boolean serverMode;
    private final AuthRateLimiter limiter;

    public AuthController(AuthService auth, RoleCaps roleCaps, AuditService audit,
                          TotpService totpService, RecoveryService recoveryService,
                          UserMapper users,
                          com.worknote.setting.SettingService settings,
                          RedmineTokenService redmineTokens,
                          Clock clock,
                          AuthRateLimiter limiter,
                          @Value("${worknote.mode:local}") String mode) {
        this.auth = auth;
        this.roleCaps = roleCaps;
        this.audit = audit;
        this.totpService = totpService;
        this.recoveryService = recoveryService;
        this.users = users;
        this.settings = settings;
        this.redmineTokens = redmineTokens;
        this.clock = clock;
        this.limiter = limiter;
        this.serverMode = "server".equals(mode);
    }

    @PostMapping("/login")
    public Object login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        if (limiter.isLocked("login", req.emp(), ip)) {
            audit.logRaw(req.emp(), "login.locked", null, ip);
            throw AuthException.locked("시도 횟수를 초과했습니다. 잠시 후 다시 시도하세요");
        }
        AuthService.AuthUser result;
        try {
            result = auth.login(req.emp(), req.password());
        } catch (AuthException e) {
            if (limiter.recordFailure("login", req.emp(), ip)) {
                audit.logRaw(req.emp(), "auth.lockout", "login", ip);
            }
            audit.logRaw(req.emp(), "login.fail", null, ip);   // 실패도 항상 기록 (스펙 §7)
            throw e;
        }
        limiter.recordSuccess("login", req.emp());
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

        // changeSessionId()는 id만 교체하고 내용을 유지하므로(:93) 같은 세션에서 계정을 바꿔 로그인하면
        // 직전 로그인의 인증 상태가 잔류한다 — 두 분기 모두에서 반대편 마커를 명시적으로 지운다.
        if (totpService.isEnabled(result.user().id())) {
            session.setAttribute(SESSION_2FA_PENDING, Boolean.TRUE);   // 부분 인증 — SESSION_CRED 미설정
            session.removeAttribute(SESSION_CRED);                     // 이전 완전 인증 잔류 제거
            audit.logRaw(result.user().emp(), "2fa.challenge", null, http.getRemoteAddr());
            return Map.of("status", "2fa_required");
        }
        session.setAttribute(SESSION_CRED, result.credSalt());
        session.removeAttribute(SESSION_2FA_PENDING);   // 이전 부분 인증 잔류 제거 (남으면 AuthFilter가 이 세션을 차단)
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
        String ip = http.getRemoteAddr();
        if (limiter.isLocked("2fa", userId, ip)) {
            audit.logRaw(userId, "2fa.locked", null, ip);
            throw AuthException.locked("시도 횟수를 초과했습니다. 잠시 후 다시 시도하세요");
        }
        if (!totpService.verifyLogin(userId, req.code())) {
            if (limiter.recordFailure("2fa", userId, ip)) {
                audit.logRaw(userId, "auth.lockout", "2fa", ip);
            }
            audit.logRaw(userId, "2fa.verify.fail", null, ip);
            throw AuthException.unauthorized("인증 코드가 올바르지 않습니다");
        }
        limiter.recordSuccess("2fa", userId);
        return completePending(session, userId, http, "2fa.verify.success");
    }

    /**
     * 복구는 비밀번호 인증(pending 세션) 후에만 — 이메일 수신함 탈취 단독으로
     * 1차 요소(비밀번호)까지 우회하는 매직링크화 차단 (감사 §2-3).
     * 세션 없음·pending 아님·emp 불일치 모두 동일 401 (계정 열거 차단).
     */
    private String requirePendingFor(String emp, HttpServletRequest http) {
        HttpSession session = http.getSession(false);
        String userId = session != null ? (String) session.getAttribute(SESSION_USER) : null;
        if (userId == null || !Boolean.TRUE.equals(session.getAttribute(SESSION_2FA_PENDING))) {
            throw AuthException.unauthorized("비밀번호 인증 후 복구 코드를 사용할 수 있습니다");
        }
        UserRow u = users.findById(userId);
        if (u == null || !u.emp().equals(emp)) {
            throw AuthException.unauthorized("비밀번호 인증 후 복구 코드를 사용할 수 있습니다");
        }
        return userId;
    }

    /**
     * 이메일 복구 코드 요청 — 항상 204 반환 (계정 존재 여부 노출 금지).
     * 조건 충족 시 RecoveryService가 발송; 미충족(계정없음/이메일없음/2FA미사용)이면 조용히 skip.
     * 1차 요소 선행 강제: pending 세션 없이는 진입 불가 (감사 §2-3).
     */
    @PostMapping("/2fa/recover/request")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recoverRequest(@Valid @RequestBody RecoverRequest req, HttpServletRequest http) {
        requirePendingFor(req.emp(), http);   // 1차 요소 선행 — 복구는 2차 요소의 대체일 뿐
        recoveryService.request(req.emp());   // 내부에서 조건 미충족 시 skip — 균등 응답
        audit.logRaw(req.emp(), "2fa.recover.request", null, http.getRemoteAddr());
    }

    /**
     * 이메일 복구 코드 검증 — 성공 시 기존 TOTP 시드 폐기 + 완전 인증 세션 승격 + MeResponse 반환.
     * 실패(코드 오류/만료/계정없음) 시 401.
     * 1차 요소 선행 강제: pending 세션 없이는 진입 불가 (감사 §2-3).
     */
    @PostMapping("/2fa/recover/verify")
    public MeResponse recoverVerify(@Valid @RequestBody RecoverVerifyRequest req, HttpServletRequest http) {
        requirePendingFor(req.emp(), http);
        String ip = http.getRemoteAddr();
        if (limiter.isLocked("recover", req.emp(), ip)) {
            audit.logRaw(req.emp(), "recover.locked", null, ip);
            throw AuthException.locked("시도 횟수를 초과했습니다. 잠시 후 다시 시도하세요");
        }
        String userId = recoveryService.verify(req.emp(), req.code());
        if (userId == null) {
            if (limiter.recordFailure("recover", req.emp(), ip)) {
                audit.logRaw(req.emp(), "auth.lockout", "recover", ip);
            }
            audit.logRaw(req.emp(), "2fa.recover.fail", null, ip);
            throw AuthException.unauthorized("복구 코드가 올바르지 않거나 만료되었습니다");
        }
        // user/cred 존재를 disable 전에 확인 — 실패 시 시드만 폐기돼 사용자가 잠기는 것 방지
        UserRow user = users.findById(userId);
        CredentialRow cred = users.findCredential(userId);
        if (user == null || cred == null) throw AuthException.unauthorized("자격 정보가 유효하지 않습니다");
        limiter.recordSuccess("recover", req.emp());
        // 복구 성공: 기존 시드 즉시 폐기(재등록 강제)
        totpService.disable(userId);
        // 완전 인증 승격 — pending 세션 재사용, 마커 제거 + id 재발급
        HttpSession session = http.getSession(false);
        http.changeSessionId();
        session.removeAttribute(SESSION_2FA_PENDING);
        session.setAttribute(SESSION_USER, userId);
        session.setAttribute(SESSION_CRED, cred.salt());
        audit.logRaw(user.emp(), "2fa.recover.success", null, ip);
        return toMe(user, auth.caps(user));
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
        // local 모드는 2FA/redmine 무관 — 전부 false
        return new MeResponse("local", "local", "local", null, "admin", roleCaps.of("admin"),
            new MeResponse.TotpInfo(false, false, false, false),
            new MeResponse.RedmineInfo(false, false));
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
            new MeResponse.TotpInfo(enabled, enforced, graceExpired, emailPresent),
            new MeResponse.RedmineInfo(settings.redmineEnabled(), redmineTokens.hasToken(user.id())));
    }
}
