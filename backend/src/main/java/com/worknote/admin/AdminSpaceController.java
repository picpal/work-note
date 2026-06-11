package com.worknote.admin;

import com.worknote.acl.SpaceRow;
import com.worknote.admin.dto.SpaceRequest;
import com.worknote.audit.AuditService;
import com.worknote.auth.AuthFilter;
import com.worknote.auth.UserRow;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/spaces")
public class AdminSpaceController {

    private final AdminGuard guard;
    private final SpaceAdminService svc;
    private final AuditService audit;

    public AdminSpaceController(AdminGuard guard, SpaceAdminService svc, AuditService audit) {
        this.guard = guard;
        this.svc = svc;
        this.audit = audit;
    }

    private static UserRow user(HttpServletRequest req) {
        return (UserRow) req.getAttribute(AuthFilter.CURRENT_USER);
    }

    @GetMapping
    public List<SpaceRow> list(HttpServletRequest req) {
        guard.requireAdmin(user(req));
        return svc.list();
    }

    @PutMapping("/{nodeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void set(@PathVariable String nodeId, @Valid @RequestBody SpaceRequest body, HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        svc.set(nodeId, body.teamId());
        audit.log(actor, "space.set", nodeId + " -> " + (body.teamId() != null ? body.teamId() : "공용"),
            req.getRemoteAddr());
    }

    @DeleteMapping("/{nodeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unset(@PathVariable String nodeId, HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        svc.unset(nodeId);
        audit.log(actor, "space.unset", nodeId, req.getRemoteAddr());
    }
}
