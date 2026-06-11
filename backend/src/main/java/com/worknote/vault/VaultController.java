package com.worknote.vault;

import com.worknote.audit.AuditService;
import com.worknote.auth.AuthFilter;
import com.worknote.auth.UserRow;
import com.worknote.vault.dto.CreateNodeRequest;
import com.worknote.vault.dto.MoveNodeRequest;
import com.worknote.vault.dto.UpdateNodeRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** vault REST API. 권한은 VaultGuard(앞단), 도메인 검증은 VaultService, HTTP 매핑은 ApiExceptionHandler. */
@RestController
@RequestMapping("/api")
public class VaultController {

    private final VaultService svc;
    private final VaultGuard guard;
    private final AuditService audit;

    public VaultController(VaultService svc, VaultGuard guard, AuditService audit) {
        this.svc = svc;
        this.guard = guard;
        this.audit = audit;
    }

    /** server 모드에선 AuthFilter가 적재한 사용자, local 모드는 null(무인증). */
    private static UserRow user(HttpServletRequest req) {
        return (UserRow) req.getAttribute(AuthFilter.CURRENT_USER);
    }

    @GetMapping("/tree")
    public List<VaultNode> tree(HttpServletRequest req) {
        return svc.tree(guard.readableIds(user(req)));
    }

    @PostMapping("/nodes")
    public ResponseEntity<VaultNode> create(@Valid @RequestBody CreateNodeRequest body, HttpServletRequest req) {
        UserRow user = user(req);
        guard.requireCreate(user, body.parentId());
        String id = body.id() != null ? body.id() : UUID.randomUUID().toString();
        VaultNode node = svc.create(id, body.parentId(), body.type(), body.name(), body.content());
        audit.log(user, "node.create", id, req.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(node);
    }

    @PatchMapping("/nodes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(@PathVariable String id, @RequestBody UpdateNodeRequest body, HttpServletRequest req) {
        guard.requireEdit(user(req), id);
        svc.update(id, body.name(), body.content(), body.tags());
        // PATCH는 1.5초 디바운스 고빈도 — 감사 제외 (스펙 §7 감사 목록에 편집 없음)
    }

    @PostMapping("/nodes/{id}/move")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void move(@PathVariable String id, @RequestBody MoveNodeRequest body, HttpServletRequest req) {
        UserRow user = user(req);
        guard.requireMove(user, id, body.parentId());
        svc.move(id, body.parentId());
        audit.log(user, "node.move", id, req.getRemoteAddr());
    }

    @DeleteMapping("/nodes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void trash(@PathVariable String id, HttpServletRequest req) {
        UserRow user = user(req);
        guard.requireDelete(user, id);
        svc.trash(id, guard.who(user));
        audit.log(user, "node.trash", id, req.getRemoteAddr());
    }

    @GetMapping("/trash")
    public List<VaultNode> trashList(HttpServletRequest req) {
        return svc.trashList(guard.trashFilter(user(req)));
    }

    @PostMapping("/trash/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restore(@PathVariable String id, HttpServletRequest req) {
        UserRow user = user(req);
        guard.requireRestore(user, id);
        svc.restore(id);
        audit.log(user, "node.restore", id, req.getRemoteAddr());
    }

    @DeleteMapping("/trash/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void purge(@PathVariable String id, HttpServletRequest req) {
        UserRow user = user(req);
        guard.requirePurge(user);
        svc.purge(id);
        audit.log(user, "node.purge", id, req.getRemoteAddr());
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
