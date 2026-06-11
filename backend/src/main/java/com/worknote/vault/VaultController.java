package com.worknote.vault;

import com.worknote.vault.dto.CreateNodeRequest;
import com.worknote.vault.dto.MoveNodeRequest;
import com.worknote.vault.dto.UpdateNodeRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** vault REST API. 도메인 검증·예외는 VaultService, HTTP 매핑은 ApiExceptionHandler. */
@RestController
@RequestMapping("/api")
public class VaultController {

    /** 1단계 단일 사용자 — deleted_by 고정값. */
    private static final String LOCAL_USER = "local";

    private final VaultService svc;

    public VaultController(VaultService svc) {
        this.svc = svc;
    }

    @GetMapping("/tree")
    public List<VaultNode> tree() {
        return svc.tree();
    }

    @PostMapping("/nodes")
    public ResponseEntity<VaultNode> create(@Valid @RequestBody CreateNodeRequest req) {
        String id = req.id() != null ? req.id() : UUID.randomUUID().toString();
        VaultNode node = svc.create(id, req.parentId(), req.type(), req.name(), req.content());
        return ResponseEntity.status(HttpStatus.CREATED).body(node);
    }

    @PatchMapping("/nodes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(@PathVariable String id, @RequestBody UpdateNodeRequest req) {
        svc.update(id, req.name(), req.content(), req.tags());
    }

    @PostMapping("/nodes/{id}/move")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void move(@PathVariable String id, @RequestBody MoveNodeRequest req) {
        svc.move(id, req.parentId());
    }

    @DeleteMapping("/nodes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void trash(@PathVariable String id) {
        svc.trash(id, LOCAL_USER);
    }

    @GetMapping("/trash")
    public List<VaultNode> trashList() {
        return svc.trashList();
    }

    @PostMapping("/trash/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restore(@PathVariable String id) {
        svc.restore(id);
    }

    @DeleteMapping("/trash/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void purge(@PathVariable String id) {
        svc.purge(id);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
