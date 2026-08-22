package com.worknote.template;

import com.worknote.vault.VaultException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 노트 템플릿 CRUD. 노트 ACL 엔진은 개입하지 않는다 — 템플릿은 리소스 트리 밖 객체이고
 * 권한 판정은 소유자 일치 여부뿐이다(시스템 템플릿은 AdminGuard가 별도로 막는다).
 *
 * <p>검증은 이 클래스가 단일 출처다. DTO에 Bean Validation을 달면 같은 성격의 실패가
 * 400(MethodArgumentNotValid)과 422(VaultException.invalid)로 갈리므로 쓰지 않는다.
 */
@Service
public class NoteTemplateService {

    private final NoteTemplateMapper mapper;

    public NoteTemplateService(NoteTemplateMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<NoteTemplateRow> listVisible(String ownerId) {
        return mapper.listVisible(ownerId);
    }

    @Transactional(readOnly = true)
    public List<NoteTemplateRow> listSystem() {
        return mapper.listSystem();
    }

    /** ownerId == null 이면 시스템 템플릿을 만든다(관리자 경로 전용 — 개수 상한 없음). */
    @Transactional
    public NoteTemplateRow create(String ownerId, String name, String body) {
        validate(name, body);
        if (ownerId != null && mapper.countByOwner(ownerId) >= TemplateLimits.PER_OWNER_MAX) {
            throw VaultException.invalid(
                "템플릿은 최대 " + TemplateLimits.PER_OWNER_MAX + "개까지 만들 수 있습니다");
        }
        String now = now();
        NoteTemplateRow row = new NoteTemplateRow(
            UUID.randomUUID().toString(), ownerId, name.trim(), body, now, now);
        mapper.insert(row);
        return row;
    }

    /** system=true면 시스템 템플릿만, false면 ownerId 소유분만 고칠 수 있다. */
    @Transactional
    public NoteTemplateRow update(String id, String ownerId, boolean system, String name, String body) {
        validate(name, body);
        NoteTemplateRow cur = requireWritable(id, ownerId, system);
        NoteTemplateRow next = new NoteTemplateRow(
            cur.id(), cur.ownerId(), name.trim(), body, cur.createdAt(), now());
        mapper.update(next);
        return next;
    }

    /** 삭제 전 행을 돌려준다 — 관리자 경로가 감사 로그에 이름을 남기는 데 쓴다. */
    @Transactional
    public NoteTemplateRow delete(String id, String ownerId, boolean system) {
        NoteTemplateRow cur = requireWritable(id, ownerId, system);
        mapper.delete(id);
        return cur;
    }

    private NoteTemplateRow requireWritable(String id, String ownerId, boolean system) {
        NoteTemplateRow cur = mapper.find(id);
        if (cur == null) {
            throw VaultException.notFound("템플릿을 찾을 수 없습니다");
        }
        if (system) {
            if (cur.ownerId() != null) {
                throw VaultException.forbidden("시스템 템플릿이 아닙니다");
            }
        } else if (!Objects.equals(cur.ownerId(), ownerId)) {
            // 시스템 템플릿(ownerId=null)과 타인 소유 모두 여기서 막힌다.
            // 404가 아니라 403인 이유: id는 UUID라 열거 가치가 없고, 403이 원인을 정확히 알려 준다.
            throw VaultException.forbidden("내 템플릿만 수정할 수 있습니다");
        }
        return cur;
    }

    private static void validate(String name, String body) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            throw VaultException.invalid("템플릿 이름을 입력하세요");
        }
        if (n.length() > TemplateLimits.NAME_MAX) {
            throw VaultException.invalid("이름은 " + TemplateLimits.NAME_MAX + "자 이하여야 합니다");
        }
        if (body == null || body.isBlank()) {
            throw VaultException.invalid("템플릿 본문이 비어 있습니다");
        }
        if (body.length() > TemplateLimits.BODY_MAX) {
            throw VaultException.invalid("본문이 너무 깁니다");
        }
    }

    private static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
