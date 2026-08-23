package com.worknote.template;

import com.worknote.auth.AuthFilter;
import com.worknote.auth.UserRow;
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

/** 사용자 템플릿 API — 시스템 템플릿은 읽기만 되고 개인 템플릿만 쓸 수 있다. */
@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    public record TemplateRequest(String name, String body) {}

    public record TemplateResponse(String id, String name, String body, boolean system) {
        public static TemplateResponse of(NoteTemplateRow r) {
            return new TemplateResponse(r.id(), r.name(), r.body(), r.ownerId() == null);
        }
    }

    private final NoteTemplateService service;

    public TemplateController(NoteTemplateService service) {
        this.service = service;
    }

    /**
     * 템플릿 소유자 키. local 모드는 AuthFilter가 등록되지 않아 CURRENT_USER가 null이고
     * app_user 행도 없으므로 단일 사용자를 뜻하는 상수 'local'을 쓴다.
     */
    static String ownerKey(HttpServletRequest req) {
        UserRow u = (UserRow) req.getAttribute(AuthFilter.CURRENT_USER);
        return u != null ? u.id() : "local";
    }

    @GetMapping
    public List<TemplateResponse> list(HttpServletRequest req) {
        return service.listVisible(ownerKey(req)).stream().map(TemplateResponse::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateResponse create(@RequestBody TemplateRequest body, HttpServletRequest req) {
        return TemplateResponse.of(service.create(ownerKey(req), body.name(), body.body()));
    }

    @PutMapping("/{id}")
    public TemplateResponse update(@PathVariable String id, @RequestBody TemplateRequest body,
                                   HttpServletRequest req) {
        return TemplateResponse.of(service.update(id, ownerKey(req), false, body.name(), body.body()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, HttpServletRequest req) {
        service.delete(id, ownerKey(req), false);
    }
}
