package com.worknote.auth;

import com.worknote.auth.dto.LoginRequest;
import com.worknote.auth.dto.MeResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/** 세션 기반 인증 API. server 모드에선 AuthFilter가 login/health 외 전부를 가드한다. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public static final String SESSION_USER = "worknote.userId";

    private final AuthService auth;
    private final boolean serverMode;

    public AuthController(AuthService auth, @Value("${worknote.mode:local}") String mode) {
        this.auth = auth;
        this.serverMode = "server".equals(mode);
    }

    @PostMapping("/login")
    public MeResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        AuthService.AuthUser result = auth.login(req.emp(), req.password());
        http.getSession(true).setAttribute(SESSION_USER, result.user().id());
        return toMe(result.user(), result.caps());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest http) {
        HttpSession session = http.getSession(false);
        if (session != null) {
            session.invalidate();
        }
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
        return new MeResponse("local", "local", "local", "admin", Set.of());  // 1단계 호환
    }

    private static MeResponse toMe(UserRow user, Set<String> caps) {
        return new MeResponse(user.id(), user.emp(), user.name(), user.roleId(), caps);
    }
}
