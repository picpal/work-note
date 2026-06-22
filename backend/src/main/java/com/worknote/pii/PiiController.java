package com.worknote.pii;

import com.worknote.admin.AdminGuard;
import com.worknote.audit.AuditService;
import com.worknote.auth.AuthFilter;
import com.worknote.auth.UserRow;
import com.worknote.vault.VaultGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** PII 노트 표시·예외 요청(노트 write) / 관리자 점검·결정(admin) / 내 알림(me). */
@RestController
@RequestMapping("/api")
public class PiiController {

    private final PiiService pii;
    private final VaultGuard vaultGuard;
    private final AdminGuard adminGuard;
    private final AuditService audit;

    public PiiController(PiiService pii, VaultGuard vaultGuard, AdminGuard adminGuard, AuditService audit) {
        this.pii = pii;
        this.vaultGuard = vaultGuard;
        this.adminGuard = adminGuard;
        this.audit = audit;
    }

    private static UserRow user(HttpServletRequest req) {
        return (UserRow) req.getAttribute(AuthFilter.CURRENT_USER);
    }

    @PostMapping("/nodes/{id}/pii/exception")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void requestException(@PathVariable String id, @RequestBody(required = false) Map<String, String> body,
                                 HttpServletRequest req) {
        UserRow u = user(req);
        vaultGuard.requireEdit(u, id);
        String reason = body == null ? null : body.get("reason");
        pii.requestException(id, vaultGuard.who(u), reason);
        audit.log(u, "pii.request", id, req.getRemoteAddr());
    }

    @GetMapping("/admin/pii/notes")
    public List<Map<String, Object>> adminNotes(HttpServletRequest req) {
        adminGuard.requireAdmin(user(req));
        return pii.adminList();
    }

    @GetMapping("/admin/pii/notes/{id}/content")
    public PiiContentResponse adminNoteContent(@PathVariable String id, HttpServletRequest req) {
        UserRow u = user(req);
        adminGuard.requireAdmin(u);
        PiiContentResponse res = pii.noteContent(id);
        audit.log(u, "pii.view", id, req.getRemoteAddr());
        return res;
    }

    @GetMapping("/admin/pii/requests")
    public List<Map<String, Object>> adminRequests(HttpServletRequest req) {
        adminGuard.requireAdmin(user(req));
        return pii.adminRequests();
    }

    @PostMapping("/admin/pii/notes/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@PathVariable String id, HttpServletRequest req) {
        UserRow u = user(req);
        adminGuard.requireAdmin(u);
        pii.approveWithNotice(id, vaultGuard.who(u));
        audit.log(u, "pii.approve", id, req.getRemoteAddr());
    }

    @PostMapping("/admin/pii/notes/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@PathVariable String id, @RequestBody(required = false) Map<String, String> body,
                       HttpServletRequest req) {
        UserRow u = user(req);
        adminGuard.requireAdmin(u);
        pii.rejectWithNotice(id, vaultGuard.who(u), body == null ? null : body.get("reason"));
        audit.log(u, "pii.reject", id, req.getRemoteAddr());
    }

    @PostMapping("/admin/pii/notes/{id}/notice")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void notice(@PathVariable String id, HttpServletRequest req) {
        UserRow u = user(req);
        adminGuard.requireAdmin(u);
        String recipient = pii.recipientForNotice(id);
        pii.notice(id, recipient, vaultGuard.who(u));
        audit.log(u, "pii.notice", id, req.getRemoteAddr());
    }

    @GetMapping("/me/pii-notices")
    public List<Map<String, Object>> myNotices(HttpServletRequest req) {
        UserRow u = user(req);
        return pii.noticesFor(vaultGuard.who(u));
    }

    @PostMapping("/me/pii-notices/ack")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ack(@RequestBody(required = false) Map<String, List<Number>> body, HttpServletRequest req) {
        UserRow u = user(req);
        List<Long> ids = null;
        if (body != null && body.get("ids") != null) {
            ids = body.get("ids").stream().map(Number::longValue).toList();
        }
        pii.ack(vaultGuard.who(u), ids);
    }
}
