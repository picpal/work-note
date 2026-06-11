package com.worknote.admin;

import com.worknote.acl.AclRow;
import com.worknote.admin.dto.SetAclRequest;
import com.worknote.audit.AuditService;
import com.worknote.auth.AuthFilter;
import com.worknote.auth.UserRow;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** ACL 관리 엔드포인트. public_flag 엔드포인트는 Task 8에서 이 컨트롤러에 추가. */
@RestController
@RequestMapping("/api/admin")
public class AdminAclController {

    private final AdminGuard guard;
    private final AclAdminService svc;
    private final AuditService audit;

    public AdminAclController(AdminGuard guard, AclAdminService svc, AuditService audit) {
        this.guard = guard;
        this.svc = svc;
        this.audit = audit;
    }

    private static UserRow user(HttpServletRequest req) {
        return (UserRow) req.getAttribute(AuthFilter.CURRENT_USER);
    }

    @GetMapping("/acl")
    public List<AclRow> listAll(HttpServletRequest req) {
        guard.requireAdmin(user(req));
        return svc.listAll();
    }

    @GetMapping("/nodes/{id}/acl")
    public List<AclRow> forNode(@PathVariable String id, HttpServletRequest req) {
        guard.requireAdmin(user(req));
        return svc.forNode(id);
    }

    @PutMapping("/nodes/{id}/acl")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void replace(@PathVariable String id, @Valid @RequestBody SetAclRequest body, HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        String suffix = svc.replace(id, body.entries());
        audit.log(actor, "acl.set", id + " (" + body.entries().size() + "건)" + suffix, req.getRemoteAddr());
    }
}
