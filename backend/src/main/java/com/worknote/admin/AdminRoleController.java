package com.worknote.admin;

import com.worknote.admin.dto.CreateRoleRequest;
import com.worknote.admin.dto.UpdateRoleRequest;
import com.worknote.audit.AuditService;
import com.worknote.auth.AuthFilter;
import com.worknote.auth.UserRow;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/roles")
public class AdminRoleController {

    private final AdminGuard guard;
    private final RoleAdminService svc;
    private final AuditService audit;

    public AdminRoleController(AdminGuard guard, RoleAdminService svc, AuditService audit) {
        this.guard = guard;
        this.svc = svc;
        this.audit = audit;
    }

    private static UserRow user(HttpServletRequest req) {
        return (UserRow) req.getAttribute(AuthFilter.CURRENT_USER);
    }

    @GetMapping
    public List<RoleAdminService.RoleView> list(HttpServletRequest req) {
        guard.requireAdmin(user(req));
        return svc.list();
    }

    @PostMapping
    public ResponseEntity<RoleAdminService.RoleView> create(@Valid @RequestBody CreateRoleRequest body,
                                                            HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        RoleAdminService.RoleView created = svc.create(body.id(), body.name(), body.caps());
        audit.log(actor, "role.create", body.id(), req.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{id}")
    public RoleAdminService.RoleView update(@PathVariable String id, @Valid @RequestBody UpdateRoleRequest body,
                                            HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        RoleAdminService.RoleView updated = svc.update(id, body.name(), body.caps());
        audit.log(actor, "role.update", id, req.getRemoteAddr());
        return updated;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        svc.delete(id);
        audit.log(actor, "role.delete", id, req.getRemoteAddr());
    }
}
