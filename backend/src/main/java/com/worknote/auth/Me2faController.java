package com.worknote.auth;

import com.worknote.acl.AclResolver;
import com.worknote.auth.dto.TotpConfirmRequest;
import com.worknote.auth.dto.TotpSetupResponse;
import com.worknote.auth.totp.QrPng;
import com.worknote.auth.totp.TotpService;
import com.worknote.audit.AuditService;
import com.worknote.vault.VaultException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/**
 * 본인 2FA 등록/확인/QR/해제.
 * server 모드에서 AuthFilter(/api/*)가 완전 인증을 보장. ENFORCE_ALLOWLIST에 setup/qr/confirm/me가 포함되어
 * grace 만료 admin도 이 엔드포인트를 통해 등록 가능.
 */
@RestController
@RequestMapping("/api/me/2fa")
public class Me2faController {

    private final TotpService totp;
    private final RoleCaps roleCaps;
    private final AuditService audit;

    public Me2faController(TotpService totp, RoleCaps roleCaps, AuditService audit) {
        this.totp = totp;
        this.roleCaps = roleCaps;
        this.audit = audit;
    }

    private UserRow me(HttpServletRequest http) {
        UserRow u = (UserRow) http.getAttribute(AuthFilter.CURRENT_USER);
        if (u == null) throw AuthException.forbidden("로그인 상태에서만 가능합니다");
        return u;
    }

    /** 2FA 시드 생성(또는 재생성). 이메일 미등록 시 422(복구 불가 방지). */
    @PostMapping("/setup")
    public TotpSetupResponse setup(HttpServletRequest http) {
        UserRow u = me(http);
        if (u.email() == null || u.email().isBlank()) {
            throw VaultException.invalid("복구를 위해 먼저 이메일을 등록하세요 (프로필에서 이메일 추가)");
        }
        String uri = totp.setup(u.id(), u.emp());
        audit.log(u, "2fa.setup", null, http.getRemoteAddr());
        return new TotpSetupResponse(uri);
    }

    /** 등록 중인 시드의 QR PNG 반환 (zxing 오프라인 생성). */
    @GetMapping(value = "/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] qr(HttpServletRequest http) {
        UserRow u = me(http);
        String uri = totp.otpauthUriForExisting(u.id(), u.emp());
        return QrPng.encode(uri, 220);
    }

    /** 등록 완료 확인 — 코드 검증 후 enabled=1. 실패 시 422. */
    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@Valid @RequestBody TotpConfirmRequest req, HttpServletRequest http) {
        UserRow u = me(http);
        if (!totp.confirm(u.id(), req.code())) {
            throw VaultException.invalid("인증 코드가 올바르지 않습니다");
        }
        audit.log(u, "2fa.enabled", null, http.getRemoteAddr());
    }

    /**
     * 본인 2FA 해제.
     * enforced admin (admin 역할 보유 + 현재 2FA 활성 상태) → 403 ("관리자는 2FA를 비활성화할 수 없습니다").
     * 비-admin은 허용.
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(HttpServletRequest http) {
        UserRow u = me(http);
        boolean isAdmin = roleCaps.of(u.roleId()).containsAll(AclResolver.ADMIN_CAPS);
        if (isAdmin && totp.isEnabled(u.id())) {
            throw AuthException.forbidden("관리자는 2FA를 비활성화할 수 없습니다");
        }
        totp.disable(u.id());
        audit.log(u, "2fa.disabled", null, http.getRemoteAddr());
    }
}
