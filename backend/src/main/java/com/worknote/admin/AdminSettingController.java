package com.worknote.admin;

import com.worknote.admin.dto.UploadPolicyRequest;
import com.worknote.attachment.UploadPolicy;
import com.worknote.audit.AuditService;
import com.worknote.auth.AuthFilter;
import com.worknote.auth.UserRow;
import com.worknote.setting.SettingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 업로드 정책 조회/변경 + Redmine 연동 설정. app_setting을 단일 출처로. */
@RestController
@RequestMapping("/api/admin")
public class AdminSettingController {

    public record RedmineConfig(boolean enabled, String baseUrl) {}

    private final SettingService settings;
    private final AdminGuard guard;
    private final AuditService audit;

    public AdminSettingController(SettingService settings, AdminGuard guard, AuditService audit) {
        this.settings = settings;
        this.guard = guard;
        this.audit = audit;
    }

    private static UserRow user(HttpServletRequest req) {
        return (UserRow) req.getAttribute(AuthFilter.CURRENT_USER);
    }

    @GetMapping("/settings/upload")
    public Map<String, Object> get(HttpServletRequest req) {
        guard.requireAdmin(user(req));
        UploadPolicy p = settings.uploadPolicy();
        return Map.of("allowedExt", List.copyOf(p.allowedExt()), "maxBytes", p.maxBytes());
    }

    @PutMapping("/settings/upload")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void set(@Valid @RequestBody UploadPolicyRequest body, HttpServletRequest req) {
        UserRow u = user(req);
        guard.requireAdmin(u);
        settings.setUploadPolicy(body.allowedExt(), body.maxBytes());
        audit.log(u, "settings.upload",
            "ext=" + body.allowedExt().size() + " max=" + body.maxBytes(), req.getRemoteAddr());
    }

    // ─── Redmine 연동 설정 ─────────────────────────────────────────────────

    @GetMapping("/settings/redmine")
    public RedmineConfig getRedmine(HttpServletRequest req) {
        guard.requireAdmin(user(req));
        return new RedmineConfig(settings.redmineEnabled(), settings.redmineBaseUrl());
    }

    @PutMapping("/settings/redmine")
    public RedmineConfig setRedmine(@RequestBody RedmineConfig cfg, HttpServletRequest req) {
        UserRow u = user(req);
        guard.requireAdmin(u);
        settings.setRedmine(cfg.enabled(), cfg.baseUrl());
        audit.log(u, "settings.redmine",
            "enabled=" + cfg.enabled() + " url=" + cfg.baseUrl(), req.getRemoteAddr());
        return new RedmineConfig(settings.redmineEnabled(), settings.redmineBaseUrl());
    }
}
