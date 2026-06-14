package com.worknote.attachment;

import com.worknote.audit.AuditService;
import com.worknote.auth.AuthFilter;
import com.worknote.auth.UserRow;
import com.worknote.share.ShareLinkService;
import com.worknote.vault.VaultException;
import com.worknote.vault.VaultGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 첨부 REST API. 업로드/삭제=노트 write, 열람=노트 read. 공유 서빙은 토큰이 통제(VaultGuard 없음). */
@RestController
@RequestMapping("/api")
public class AttachmentController {

    private final AttachmentService svc;
    private final VaultGuard guard;
    private final ShareLinkService share;
    private final AuditService audit;

    public AttachmentController(AttachmentService svc, VaultGuard guard, ShareLinkService share,
                                AuditService audit) {
        this.svc = svc;
        this.guard = guard;
        this.share = share;
        this.audit = audit;
    }

    /** server 모드에선 AuthFilter가 적재한 사용자, local 모드는 null(무인증). */
    private static UserRow user(HttpServletRequest req) {
        return (UserRow) req.getAttribute(AuthFilter.CURRENT_USER);
    }

    @PostMapping("/nodes/{id}/attachments")
    public ResponseEntity<Map<String, Object>> upload(@PathVariable String id,
            @RequestParam("file") MultipartFile file, HttpServletRequest req) throws IOException {
        UserRow user = user(req);
        guard.requireEdit(user, id); // 업로드 = 노트 편집 권한
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            throw VaultException.invalid("파일명이 없습니다");
        }
        AttachmentRow row = svc.store(id, name, file.getBytes(), guard.who(user));
        audit.log(user, "attachment.add", row.id() + " -> " + id, req.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "id", row.id(), "filename", row.filename(), "size", row.size(),
            "url", "/api/attachments/" + row.id()));
    }

    /** 노트의 첨부 목록 (read 권한). 본문 마크다운과 무관하게 attachment 테이블이 출처. */
    @GetMapping("/nodes/{id}/attachments")
    public List<Map<String, Object>> list(@PathVariable String id, HttpServletRequest req) {
        UserRow user = user(req);
        guard.requireRead(user, id);
        return svc.findByNode(id).stream()
            .map(r -> meta(r, "/api/attachments/" + r.id()))
            .toList();
    }

    @GetMapping("/attachments/{id}")
    public ResponseEntity<byte[]> download(@PathVariable String id, HttpServletRequest req) {
        UserRow user = user(req);
        AttachmentRow row = svc.findById(id);
        if (row == null) {
            throw VaultException.notFound("첨부를 찾을 수 없습니다");
        }
        guard.requireRead(user, row.nodeId()); // 열람 = 노트 read
        return serve(row);
    }

    @DeleteMapping("/attachments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, HttpServletRequest req) {
        UserRow user = user(req);
        AttachmentRow row = svc.findById(id);
        if (row == null) {
            throw VaultException.notFound("첨부를 찾을 수 없습니다");
        }
        guard.requireEdit(user, row.nodeId());
        svc.delete(id);
        audit.log(user, "attachment.remove", id + " -> " + row.nodeId(), req.getRemoteAddr());
    }

    /** 공유 노트의 첨부 목록 — 토큰 검증(비증가). url은 토큰 스코프 서빙 경로. */
    @GetMapping("/share/{token}/attachments")
    public List<Map<String, Object>> shareList(@PathVariable String token, HttpServletRequest req) {
        UserRow user = user(req);
        String nodeId = share.nodeIdForAttachment(token, user == null ? null : user.emp());
        return svc.findByNode(nodeId).stream()
            .map(r -> meta(r, "/api/share/" + token + "/attachments/" + r.id()))
            .toList();
    }

    /** 공유 서빙 — 토큰 검증(비증가) + 첨부가 그 노드 소속인지. 무효 전부 404. */
    @GetMapping("/share/{token}/attachments/{id}")
    public ResponseEntity<byte[]> shareDownload(@PathVariable String token, @PathVariable String id,
            HttpServletRequest req) {
        UserRow user = user(req);
        String nodeId = share.nodeIdForAttachment(token, user == null ? null : user.emp());
        AttachmentRow row = svc.findById(id);
        if (row == null || !row.nodeId().equals(nodeId)) {
            throw VaultException.notFound("첨부를 찾을 수 없습니다");
        }
        return serve(row);
    }

    /** 첨부 메타 직렬화 — 다운로드 url은 호출 맥락(일반/공유)에 따라 주입. */
    private static Map<String, Object> meta(AttachmentRow r, String url) {
        return Map.of(
            "id", r.id(), "filename", r.filename(), "size", r.size(),
            "mime", r.mime(), "image", UploadPolicy.isImage(r.ext()), "url", url);
    }

    private ResponseEntity<byte[]> serve(AttachmentRow row) {
        byte[] bytes = svc.read(row);
        boolean image = UploadPolicy.isImage(row.ext());
        String dispo = (image ? "inline" : "attachment") + "; filename*=UTF-8''"
            + URLEncoder.encode(row.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, row.mime())
            .header(HttpHeaders.CONTENT_DISPOSITION, dispo)
            .header("X-Content-Type-Options", "nosniff")
            .body(bytes);
    }
}
