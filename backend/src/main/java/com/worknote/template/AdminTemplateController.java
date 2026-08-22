package com.worknote.template;

import com.worknote.admin.AdminGuard;
import com.worknote.audit.AuditService;
import com.worknote.auth.AuthFilter;
import com.worknote.auth.UserRow;
import com.worknote.template.TemplateController.TemplateRequest;
import com.worknote.template.TemplateController.TemplateResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 시스템 템플릿 API. owner_id = NULL 만 다루므로 개인 템플릿은 이 경로로 건드릴 수 없다
 * (service.update/delete에 system=true를 넘겨 강제).
 */
@RestController
@RequestMapping("/api/admin/templates")
public class AdminTemplateController {

    private final NoteTemplateService service;
    private final AdminGuard guard;
    private final AuditService audit;

    public AdminTemplateController(NoteTemplateService service, AdminGuard guard, AuditService audit) {
        this.service = service;
        this.guard = guard;
        this.audit = audit;
    }

    private static UserRow user(HttpServletRequest req) {
        return (UserRow) req.getAttribute(AuthFilter.CURRENT_USER);
    }

    @GetMapping
    public List<TemplateResponse> list(HttpServletRequest req) {
        guard.requireAdmin(user(req));
        return service.listSystem().stream().map(TemplateResponse::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateResponse create(@RequestBody TemplateRequest body, HttpServletRequest req) {
        UserRow u = user(req);
        guard.requireAdmin(u);
        NoteTemplateRow row = service.create(null, body.name(), body.body());
        audit.log(u, "template.system.create", row.name(), req.getRemoteAddr());
        return TemplateResponse.of(row);
    }

    @PutMapping("/{id}")
    public TemplateResponse update(@PathVariable String id, @RequestBody TemplateRequest body,
                                   HttpServletRequest req) {
        UserRow u = user(req);
        guard.requireAdmin(u);
        NoteTemplateRow row = service.update(id, null, true, body.name(), body.body());
        audit.log(u, "template.system.update", row.name(), req.getRemoteAddr());
        return TemplateResponse.of(row);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, HttpServletRequest req) {
        UserRow u = user(req);
        guard.requireAdmin(u);
        NoteTemplateRow row = service.delete(id, null, true);
        audit.log(u, "template.system.delete", row.name(), req.getRemoteAddr());
    }
}
