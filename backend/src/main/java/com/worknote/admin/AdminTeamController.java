package com.worknote.admin;

import com.worknote.acl.TeamRow;
import com.worknote.admin.dto.TeamMemberRequest;
import com.worknote.admin.dto.TeamRequest;
import com.worknote.audit.AuditService;
import com.worknote.auth.AuthFilter;
import com.worknote.auth.UserRow;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/teams")
public class AdminTeamController {

    private final AdminGuard guard;
    private final TeamAdminService svc;
    private final AuditService audit;

    public AdminTeamController(AdminGuard guard, TeamAdminService svc, AuditService audit) {
        this.guard = guard;
        this.svc = svc;
        this.audit = audit;
    }

    private static UserRow user(HttpServletRequest req) {
        return (UserRow) req.getAttribute(AuthFilter.CURRENT_USER);
    }

    @GetMapping
    public List<TeamAdminService.TeamView> list(HttpServletRequest req) {
        guard.requireAdmin(user(req));
        return svc.list();
    }

    @PostMapping
    public ResponseEntity<TeamRow> create(@Valid @RequestBody TeamRequest body, HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        TeamRow created = svc.create(body.name());
        audit.log(actor, "team.create", created.id(), req.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rename(@PathVariable String id, @Valid @RequestBody TeamRequest body, HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        svc.rename(id, body.name());
        audit.log(actor, "team.update", id, req.getRemoteAddr());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        svc.delete(id);
        audit.log(actor, "team.delete", id, req.getRemoteAddr());
    }

    @PostMapping("/{id}/members")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addMember(@PathVariable String id, @Valid @RequestBody TeamMemberRequest body,
                          HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        UserRow member = svc.addMember(id, body.userId());
        audit.log(actor, "team.member.add", id + " + " + member.emp(), req.getRemoteAddr());
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable String id, @PathVariable String userId, HttpServletRequest req) {
        UserRow actor = user(req);
        guard.requireAdmin(actor);
        UserRow member = svc.removeMember(id, userId);
        // member.add와 동일하게 emp로 기록 — 고아 멤버십 행(사용자 삭제 후 잔존)만 userId 폴백
        String target = id + " - " + (member != null ? member.emp() : userId);
        audit.log(actor, "team.member.remove", target, req.getRemoteAddr());
    }
}
