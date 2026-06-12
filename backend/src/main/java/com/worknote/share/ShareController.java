package com.worknote.share;

import com.worknote.audit.AuditService;
import com.worknote.auth.AuthFilter;
import com.worknote.auth.UserRow;
import com.worknote.share.dto.CreateShareRequest;
import com.worknote.vault.VaultGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 공유 링크 REST API (스펙 §6). 생성·노드별 목록은 VaultGuard.requireShare,
 * 열람은 인증만(read 권한 불요 — deny를 넘는 유일 예외가 본질, 통제는 만료·취소·pin·감사).
 */
@RestController
@RequestMapping("/api")
public class ShareController {

    private final ShareLinkService svc;
    private final VaultGuard guard;
    private final AuditService audit;

    public ShareController(ShareLinkService svc, VaultGuard guard, AuditService audit) {
        this.svc = svc;
        this.guard = guard;
        this.audit = audit;
    }

    /** server 모드에선 AuthFilter가 적재한 사용자, local 모드는 null(무인증). */
    private static UserRow user(HttpServletRequest req) {
        return (UserRow) req.getAttribute(AuthFilter.CURRENT_USER);
    }

    @PostMapping("/nodes/{id}/share")
    public ResponseEntity<Map<String, Object>> create(@PathVariable String id,
            @RequestBody(required = false) CreateShareRequest body, HttpServletRequest req) {
        UserRow user = user(req);
        guard.requireShare(user, id);
        CreateShareRequest b = body != null ? body : new CreateShareRequest(null, null, null);
        ShareLinkRow row = svc.create(id, guard.who(user), b.days(), b.maxViews(), b.pinEmps());
        // 감사 target에 token 원문 비기록 (결정 S6) — linkId로 추적
        audit.log(user, "share.create", row.id() + " -> " + id, req.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(Map.of("id", row.id(), "token", row.token(), "expiresAt", row.expiresAt()));
    }

    /** 활성만. privileged(관리자/local)는 전체, 그 외 본인 생성분 — 비특권 user=null은 requireShare가 차단. */
    @GetMapping("/nodes/{id}/shares")
    public List<Map<String, Object>> listForNode(@PathVariable String id, HttpServletRequest req) {
        UserRow user = user(req);
        guard.requireShare(user, id);
        return svc.listForNode(id, guard.privileged(user) ? null : user.emp())
            .stream().map(this::dto).toList();
    }

    /** 가드 없음 — server 모드 인증은 AuthFilter가 보장(ALLOWLIST 미포함). 무효는 전부 404 단일화. */
    @GetMapping("/share/{token}")
    public Map<String, Object> view(@PathVariable String token, HttpServletRequest req) {
        UserRow user = user(req);
        ShareView v = svc.resolve(token, user == null ? null : user.emp());
        audit.log(user, "share.view", v.linkId() + " -> " + v.nodeId(), req.getRemoteAddr());
        Map<String, Object> out = new LinkedHashMap<>();   // updatedAt null 가능 — Map.of 불가
        out.put("name", v.name());
        out.put("content", v.content());
        out.put("updatedAt", v.updatedAt());
        return out;
    }

    /** 생성자 본인 또는 privileged — 판정은 서비스(재취소 409 포함). */
    @DeleteMapping("/shares/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String id, HttpServletRequest req) {
        UserRow user = user(req);
        ShareLinkRow row = svc.revoke(id, guard.who(user), guard.privileged(user));
        audit.log(user, "share.revoke", row.id() + " -> " + row.nodeId(), req.getRemoteAddr());
    }

    /** pinEmps는 파싱된 배열로 노출(JSON 문자열 비노출). null 값 필드 때문에 LinkedHashMap. */
    private Map<String, Object> dto(ShareLinkRow r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.id());
        out.put("token", r.token());
        out.put("expiresAt", r.expiresAt());
        out.put("maxViews", r.maxViews());
        out.put("viewCount", r.viewCount());
        out.put("pinEmps", svc.parsePins(r.pinEmps()));
        out.put("createdBy", r.createdBy());
        out.put("createdAt", r.createdAt());
        return out;
    }
}
