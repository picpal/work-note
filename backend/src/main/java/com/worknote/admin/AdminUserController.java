package com.worknote.admin;

import com.worknote.admin.dto.CreateUserRequest;
import com.worknote.admin.dto.ResetPasswordRequest;
import com.worknote.admin.dto.UpdateUserRequest;
import com.worknote.admin.dto.UserListResponse;
import com.worknote.audit.AuditService;
import com.worknote.auth.AuthFilter;
import com.worknote.auth.UserRow;
import com.worknote.auth.totp.TotpService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminGuard guard;
    private final UserAdminService svc;
    private final AuditService audit;
    private final TotpService totpService;

    public AdminUserController(AdminGuard guard, UserAdminService svc, AuditService audit, TotpService totpService) {
        this.guard = guard;
        this.svc = svc;
        this.audit = audit;
        this.totpService = totpService;
    }

    private static UserRow user(HttpServletRequest req) {
        return (UserRow) req.getAttribute(AuthFilter.CURRENT_USER);
    }

    @GetMapping
    public List<UserListResponse> list(HttpServletRequest req) {
        guard.requireAdmin(user(req));
        return svc.list().stream()
            .map(row -> UserListResponse.of(row, totpService.isEnabled(row.id())))
            .toList();
    }

    @PostMapping
    public ResponseEntity<UserRow> create(@Valid @RequestBody CreateUserRequest body, HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        UserRow created = svc.create(body.emp(), body.name(), body.email(), body.roleId(), body.password());
        audit.log(actor, "user.create", created.emp(), req.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{id}")
    public UserRow update(@PathVariable String id, @Valid @RequestBody UpdateUserRequest body, HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        UserRow updated = svc.update(actor, id, body.name(), body.email(), body.roleId(), body.status());
        audit.log(actor, "user.update", updated.emp(), req.getRemoteAddr());
        return updated;
    }

    @PostMapping("/{id}/approve")
    public UserRow approve(@PathVariable String id, HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        UserRow approved = svc.approve(id);
        audit.log(actor, "user.approve", approved.emp(), req.getRemoteAddr());
        return approved;
    }

    @PostMapping("/{id}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@PathVariable String id, @Valid @RequestBody ResetPasswordRequest body,
                              HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        UserRow target = svc.resetPassword(id, body.password());
        audit.log(actor, "user.reset", target.emp(), req.getRemoteAddr());
    }

    @PostMapping("/{id}/2fa/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset2fa(@PathVariable String id, HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        totpService.reset(id);
        audit.log(actor, "2fa.admin.reset", id, req.getRemoteAddr());
    }
}
