package com.worknote.admin;

import com.worknote.audit.AuditMapper;
import com.worknote.auth.AuthFilter;
import com.worknote.auth.UserRow;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 감사 로그 조회. who/act 정확 일치, from/to는 ISO 문자열 사전순 비교(확정 결정 #10). 조회는 감사 기록 안 함(#13). */
@RestController
@RequestMapping("/api/admin/audit")
public class AdminAuditController {

    private static final int MAX_LIMIT = 200;

    private final AdminGuard guard;
    private final AuditMapper audit;

    public AdminAuditController(AdminGuard guard, AuditMapper audit) {
        this.guard = guard;
        this.audit = audit;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String who,
                                    @RequestParam(required = false) String act,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to,
                                    @RequestParam(defaultValue = "50") int limit,
                                    @RequestParam(defaultValue = "0") int offset,
                                    HttpServletRequest req) {
        guard.requireAdmin((UserRow) req.getAttribute(AuthFilter.CURRENT_USER));
        int cappedLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        int safeOffset = Math.max(0, offset);
        return Map.of(
            "total", audit.count(who, act, from, to),
            "rows", audit.find(who, act, from, to, cappedLimit, safeOffset));
    }
}
