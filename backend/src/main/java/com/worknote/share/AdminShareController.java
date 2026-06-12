package com.worknote.share;

import com.worknote.admin.AdminGuard;
import com.worknote.auth.AuthFilter;
import com.worknote.auth.UserRow;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 관리자 활성 공유 링크 전체 목록 — 휴지통 노드 링크 포함(suspended 표시, 결정 S14). 조회라 감사 없음. */
@RestController
@RequestMapping("/api/admin")
public class AdminShareController {

    private final AdminGuard guard;
    private final ShareLinkService svc;

    public AdminShareController(AdminGuard guard, ShareLinkService svc) {
        this.guard = guard;
        this.svc = svc;
    }

    @GetMapping("/shares")
    public List<Map<String, Object>> list(HttpServletRequest req) {
        guard.requireAdmin((UserRow) req.getAttribute(AuthFilter.CURRENT_USER));
        return svc.listActive().stream().map(this::dto).toList();
    }

    private Map<String, Object> dto(ActiveShareRow r) {
        Map<String, Object> out = new LinkedHashMap<>();   // null 값 필드(maxViews·pinEmps) 때문에 Map.of 불가
        out.put("id", r.id());
        out.put("token", r.token());
        out.put("expiresAt", r.expiresAt());
        out.put("maxViews", r.maxViews());
        out.put("viewCount", r.viewCount());
        out.put("pinEmps", svc.parsePins(r.pinEmps()));
        out.put("createdBy", r.createdBy());
        out.put("createdAt", r.createdAt());
        out.put("nodeId", r.nodeId());
        out.put("nodeName", r.nodeName());
        out.put("suspended", r.nodeDeletedAt() != null);
        return out;
    }
}
