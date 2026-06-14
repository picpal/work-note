package com.worknote.vault;

import com.worknote.acl.ExposureService;
import com.worknote.acl.MovePreview;
import com.worknote.audit.AuditService;
import com.worknote.auth.AuthFilter;
import com.worknote.auth.UserRow;
import com.worknote.pii.PiiEval;
import com.worknote.pii.PiiService;
import com.worknote.vault.dto.CreateNodeRequest;
import com.worknote.vault.dto.MoveNodeRequest;
import com.worknote.vault.dto.UpdateNodeRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
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
    private final ExposureService exposure;
    private final PiiService pii;

    public VaultController(VaultService svc, VaultGuard guard, AuditService audit, ExposureService exposure, PiiService pii) {
        this.svc = svc;
        this.guard = guard;
        this.audit = audit;
        this.exposure = exposure;
        this.pii = pii;
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
    public Map<String, Object> update(@PathVariable String id, @RequestBody UpdateNodeRequest body, HttpServletRequest req) {
        UserRow user = user(req);
        guard.requireEdit(user, id);
        svc.update(id, body.name(), body.content(), body.tags(), guard.who(user));
        // PATCH는 1.5초 디바운스 고빈도 — 감사 제외 (스펙 §7 감사 목록에 편집 없음)
        Map<String, Object> resp = new HashMap<>();
        if (body.content() != null) {   // content 변경 시에만 재탐지
            PiiEval e = pii.evaluate(id, body.content());
            resp.put("pii", Map.of("status", e.status(), "types", e.types()));
        }
        return resp;   // tags-only면 {} → 프런트는 pii 미변경으로 취급
    }

    /** 이동 미리보기 — 실제 이동 없이 노출(접근 집합/공개/스페이스) 델타만 계산. move와 동일 가드·검증으로 동일 오류(404/422). */
    @GetMapping("/nodes/{id}/move-preview")
    public MovePreview movePreview(@PathVariable String id,
                                   @RequestParam(required = false) String parentId,
                                   HttpServletRequest req) {
        UserRow user = user(req);
        guard.requireMove(user, id, parentId);
        // 이동과 동일한 검증을 선행해 동일 오류(404/422)를 준다 — preview도 실제 이동처럼 막힐 건 막힘
        svc.validateMove(id, parentId);
        return exposure.preview(id, parentId);
    }

    @PostMapping("/nodes/{id}/move")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void move(@PathVariable String id, @RequestBody MoveNodeRequest body, HttpServletRequest req) {
        UserRow user = user(req);
        guard.requireMove(user, id, body.parentId());
        MovePreview p = exposure.preview(id, body.parentId());   // mutate 전 — 이동 후 체인 가상 계산이라 순서 무관하지만 의미상 before
        svc.move(id, body.parentId());
        // target에 목적지 + 노출 변경 접미사 — 이동에 따른 노출 변경 재구성용 (스펙 §7)
        String target = id + " -> " + (body.parentId() != null ? body.parentId() : "root") + exposureSuffix(p);
        audit.log(user, "node.move", target, req.getRemoteAddr());
    }

    /** 노출 변경을 사람이 읽는 감사 접미사로 — 공개노출 시작·cross-space·접근주체 변경. 변경 없으면 빈 문자열. */
    private static String exposureSuffix(MovePreview p) {
        StringBuilder sb = new StringBuilder();
        if (p.publicAfter() && !p.publicBefore()) {
            sb.append(" [공개노출 시작]");
        }
        if (p.crossSpace()) {
            sb.append(" [cross-space: ")
                .append(p.fromSpace() != null ? p.fromSpace() : "공용")
                .append("→")
                .append(p.toSpace() != null ? p.toSpace() : "공용")
                .append("]");
        }
        if (sb.length() == 0 && (!p.added().isEmpty() || !p.removed().isEmpty())) {
            sb.append(" [접근주체 변경]");
        }
        return sb.toString();
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
