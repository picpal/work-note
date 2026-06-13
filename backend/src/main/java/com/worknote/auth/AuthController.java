package com.worknote.auth;

import com.worknote.audit.AuditService;
import com.worknote.auth.dto.ChangePasswordRequest;
import com.worknote.auth.dto.LoginRequest;
import com.worknote.auth.dto.MeResponse;
import com.worknote.auth.dto.SignupRequest;
import com.worknote.auth.dto.UpdateProfileRequest;
import com.worknote.vault.VaultException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

/** 세션 기반 인증 API. server 모드에선 AuthFilter가 login/signup/health 외 전부를 가드한다. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public static final String SESSION_USER = "worknote.userId";
    /** 로그인 시점 credential salt — AuthFilter가 매 요청 현재 DB salt와 비교해 리셋된 세션을 즉시 무효화. */
    public static final String SESSION_CRED = "worknote.credSalt";

    private final AuthService auth;
    private final RoleCaps roleCaps;
    private final AuditService audit;
    private final boolean serverMode;

    public AuthController(AuthService auth, RoleCaps roleCaps, AuditService audit,
                          @Value("${worknote.mode:local}") String mode) {
        this.auth = auth;
        this.roleCaps = roleCaps;
        this.audit = audit;
        this.serverMode = "server".equals(mode);
    }

    @PostMapping("/login")
    public MeResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
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
        session.setAttribute(SESSION_CRED, result.credSalt());
        audit.logRaw(result.user().emp(), "login.success", null, http.getRemoteAddr());
        return toMe(result.user(), result.caps());
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
        return new MeResponse("local", "local", "local", null, "admin", roleCaps.of("admin"));
    }

    private static MeResponse toMe(UserRow user, Set<String> caps) {
        return new MeResponse(user.id(), user.emp(), user.name(), user.email(), user.roleId(), caps);
    }
}
