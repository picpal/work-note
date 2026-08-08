package com.worknote.admin;

import com.worknote.acl.AclRow;
import com.worknote.acl.PublicFlagRow;
import com.worknote.admin.dto.PublicRequest;
import com.worknote.admin.dto.SetAclRequest;
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

    public AdminAclController(AdminGuard guard, AclAdminService svc) {
        this.guard = guard;
        this.svc = svc;
    }

    private static UserRow user(HttpServletRequest req) {
        return (UserRow) req.getAttribute(AuthFilter.CURRENT_USER);
    }

    @GetMapping("/acl")
    public List<AclRow> listAll(HttpServletRequest req) {
        guard.requireAdmin(user(req));
        return svc.listAll();
    }

    @GetMapping("/public")
    public List<PublicFlagRow> listPublic(HttpServletRequest req) {
        guard.requireAdmin(user(req));
        return svc.listPublicFlags();
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
        svc.replace(id, body.entries(), actor, req.getRemoteAddr());   // 감사 기록은 서비스 트랜잭션 안(T7-a)
    }

    @PutMapping("/nodes/{id}/public")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setPublic(@PathVariable String id, @Valid @RequestBody PublicRequest body,
                          HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        svc.setPublic(id, body.mode(), actor, req.getRemoteAddr());
    }

    @DeleteMapping("/nodes/{id}/public")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsetPublic(@PathVariable String id, HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        svc.unsetPublic(id, actor, req.getRemoteAddr());
    }
}
