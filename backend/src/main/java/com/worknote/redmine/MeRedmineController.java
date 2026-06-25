package com.worknote.redmine;

import com.worknote.audit.AuditService;
import com.worknote.auth.AuthException;
import com.worknote.auth.AuthFilter;
import com.worknote.auth.UserRow;
import com.worknote.setting.SettingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me/redmine")
public class MeRedmineController {
    private final RedmineTokenService tokens;
    private final SettingService settings;
    private final AuditService audit;

    public MeRedmineController(RedmineTokenService tokens,
                               SettingService settings, AuditService audit) {
        this.tokens = tokens; this.settings = settings; this.audit = audit;
    }

    public record RedmineMeStatus(boolean enabled, boolean tokenPresent,
                                  String redmineLogin, String lastVerifiedAt) {}
    public record TokenRequest(String token) {}

    private UserRow me(HttpServletRequest http) {
        UserRow u = (UserRow) http.getAttribute(AuthFilter.CURRENT_USER);
        if (u == null) throw AuthException.forbidden("로그인 상태에서만 가능합니다");
        return u;
    }

    @GetMapping
    public RedmineMeStatus status(HttpServletRequest http) {
        UserRow u = me(http);
        RedmineTokenRow r = tokens.status(u.id());
        return new RedmineMeStatus(settings.redmineEnabled(), r != null,
            r == null ? null : r.redmineLogin(), r == null ? null : r.lastVerifiedAt());
    }

    @PutMapping("/token")
    public RedmineMeStatus setToken(@RequestBody TokenRequest req, HttpServletRequest http) {
        UserRow u = me(http);
        tokens.setToken(u, req.token());
        audit.log(u, "redmine.token.set", null, http.getRemoteAddr());
        return status(http);
    }

    @DeleteMapping("/token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteToken(HttpServletRequest http) {
        UserRow u = me(http);
        tokens.delete(u.id());
        audit.log(u, "redmine.token.delete", null, http.getRemoteAddr());
    }
}
